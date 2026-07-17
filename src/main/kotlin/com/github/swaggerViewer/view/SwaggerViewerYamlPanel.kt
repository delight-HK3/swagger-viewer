package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.common.SwaggerAssetsExtractor
import com.github.swaggerViewer.service.yaml.SwaggerSpecDetector
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.milliseconds

private val LOG = Logger.getInstance(SwaggerViewerYamlPanel::class.java)

class SwaggerViewerYamlPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()

    private val panelJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(panelJob + Dispatchers.Default)
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val statusLabel = JBLabel("Ready", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }

    @Volatile private var currentFile: VirtualFile? = null
    @Volatile private var disposed = false

    private val lastSpecJson = AtomicReference<String?>(null)

    init {
        LOG.info("SwaggerViewerYamlPanel initialization started.")

        if (browser != null) {
            com.intellij.openapi.util.Disposer.register(this, browser)
        }

        buildUi()
        setupDocumentListener()
        setupFileEditorListener()
        startRefreshPipeline()

        // [수정] UI 스레드의 레이아웃 배치가 끝난 직후 최초 렌더링을 시도하도록 지연 처리
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || disposed) return@invokeLater

            val activeFile = FileEditorManager.getInstance(project).selectedFiles
                .firstOrNull { SwaggerSpecDetector.isSwaggerOrOpenApiFile(it) }

            if (activeFile != null) {
                switchToFile(activeFile)
            } else {
                browser?.loadHTML(SwaggerPreviewHtmlBuilder.buildInfoHtml("No File Active", "Please open or focus a Swagger/OpenAPI YAML/JSON file."))
                setStatus("No File Active")
            }
        }, ModalityState.any())
    }

    private fun buildUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val refreshBtn = JButton("Refresh").apply {
                addActionListener {
                    LOG.info("Manual refresh triggered for YAML/JSON")
                    lastSpecJson.set(null)
                    triggerRefresh()
                }
            }
            add(refreshBtn)
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)

        if (browser != null) {
            add(browser.component, BorderLayout.CENTER)
        } else {
            add(JBLabel("JCEF is not supported, so the preview cannot be displayed.", SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }

    private fun setupDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed || disposed) return
                    val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return

                    if (vFile == currentFile) {
                        LOG.info("Document changed: ${vFile.name}")
                        triggerRefresh()
                    }
                }
            },
            this
        )
    }

    private fun setupFileEditorListener() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val newFile = event.newFile ?: return
                    if (!SwaggerSpecDetector.isSwaggerOrOpenApiFile(newFile)) return
                    switchToFile(newFile)
                }
            }
        )
    }

    private fun switchToFile(file: VirtualFile) {
        currentFile = file
        lastSpecJson.set(null)
        triggerRefresh()
    }

    private fun triggerRefresh() {
        if (!panelJob.isActive || disposed) return
        refreshRequests.tryEmit(Unit)
    }

    @OptIn(FlowPreview::class)
    private fun startRefreshPipeline() {
        coroutineScope.launch {
            refreshRequests
                .debounce(800.milliseconds)
                .collectLatest {
                    if (project.isDisposed || disposed) return@collectLatest

                    // [수정] 백그라운드 파싱/추출이 본격적으로 시작되기 전에 즉각 UI 상에 상태를 표시합니다.
                    setStatus("Scanning...")

                    updatePreview()
                }
        }
    }

    private suspend fun updatePreview() {
        val browser = this.browser ?: return
        val file = currentFile ?: return

        try {
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                }
            }

            val rawText = readAction {
                if (project.isDisposed) null else FileDocumentManager.getInstance().getDocument(file)?.text
            }

            if (rawText.isNullOrBlank()) {
                withContext(Dispatchers.EDT) {
                    browser.loadHTML(SwaggerPreviewHtmlBuilder.buildInfoHtml("Empty File", "The active Swagger file is empty."))
                    setStatus("Empty File")
                }
                return
            }

            val specJson = try {
                convertToJson(rawText, file)
            } catch (e: Exception) {
                val errorHtml = SwaggerPreviewHtmlBuilder.buildErrorHtml("YAML/JSON Parse Error:\n${e.message}")
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) {
                        browser.loadHTML(errorHtml)
                        setStatus("Error")
                    }
                }
                return
            }

            if (specJson != lastSpecJson.get() && specJson.isNotEmpty()) {
                // [추가] JSON 파싱이 완료되고 리소스 추출 및 브라우저 로딩을 대기할 때 시각적 피드백 제공
                setStatus("Rendering...")

                lastSpecJson.set(specJson)

                val assetsDir = withContext(Dispatchers.IO) {
                    SwaggerAssetsExtractor.ensureExtracted()
                }
                val baseUrl = "${assetsDir.toPath().resolve("index.html").toUri()}?ts=${System.currentTimeMillis()}"
                val html = SwaggerPreviewHtmlBuilder.buildPreviewHtml(specJson, assetsDir)

                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) {
                        browser.loadHTML(html, baseUrl)
                        browser.component.revalidate()
                        browser.component.repaint()
                        setStatus("Rendered successfully")
                        LOG.info("Specification successfully updated in browser.")
                    }
                }
            } else {
                // 내용 변화가 없어 업데이트를 건너뛰었다면 상태를 다시 성공 혹은 Ready로 복구합니다.
                setStatus("Rendered successfully")
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                LOG.error("Render error in YAML/JSON panel", t)
                withContext(Dispatchers.EDT) {
                    if (!disposed && !project.isDisposed) {
                        browser.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(t.message ?: "Unexpected render error"))
                        setStatus("Error")
                    }
                }
            }
        }
    }

    private fun convertToJson(rawText: String, file: VirtualFile): String {
        val ext = file.extension?.lowercase()
        val node = if (ext == "yaml" || ext == "yml") {
            yamlMapper.readTree(rawText)
        } else {
            jsonMapper.readTree(rawText)
        }
        return jsonMapper.writeValueAsString(node)
    }

    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) statusLabel.text = text
        }, ModalityState.any())
    }

    override fun dispose() {
        disposed = true
        panelJob.cancel()
    }
}