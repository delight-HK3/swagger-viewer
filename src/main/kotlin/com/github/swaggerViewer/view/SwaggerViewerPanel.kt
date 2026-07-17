package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.annotation.SwaggerPreviewPipeline
import com.github.swaggerViewer.service.common.SwaggerAssetsExtractor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT // [수정 포인트] 이 임포트가 있어야 Dispatchers.EDT 가 올바르게 확장 함수로 동작합니다.
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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

private val LOG = Logger.getInstance(SwaggerViewerPanel::class.java)

class SwaggerViewerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

    private val panelJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(panelJob + Dispatchers.Default)

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val pipeline = SwaggerPreviewPipeline(project)
    private val statusLabel = JBLabel("Ready", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }

    @Volatile private var disposed = false
    private val lastSpecJson = AtomicReference<String?>(null)

    init {
        if (browser != null) {
            com.intellij.openapi.util.Disposer.register(this, browser)
        }

        buildUi()
        setupDocumentListener()
        setupFileWatcher()
        startRefreshPipeline()

        // [수정] 일반 패널도 완벽히 레이아웃 트리에 마운트된 직후 스캔을 돌리도록 수정
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && !disposed) {
                triggerRefresh()
            }
        }, ModalityState.any())
    }

    private fun buildUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val refreshBtn = JButton("Refresh").apply {
                addActionListener {
                    LOG.info("Manual refresh triggered")
                    lastSpecJson.set(null)
                    triggerRefresh()
                }
            }
            add(refreshBtn)
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)
        this.browser?.let { add(it.component, BorderLayout.CENTER) }
    }

    private fun setupDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (project.isDisposed || disposed) return
                val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (isProjectSourceFile(vFile)) {
                    LOG.info("Document changed: ${vFile.name}")
                    triggerRefresh()
                }
            }
        }, this)
    }

    private fun setupFileWatcher() {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (project.isDisposed || disposed) return
                if (events.any { it.file != null && isProjectSourceFile(it.file!!) }) {
                    LOG.info("VFS change detected")
                    triggerRefresh()
                }
            }
        })
    }

    private fun isProjectSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        if (ext != "java" && ext != "kt") return false
        val projectDir = project.guessProjectDir() ?: return false
        return VfsUtil.isAncestor(projectDir, file, false)
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

                    try {
                        // [수정 포인트] Dispatchers.EDT 문법으로 통일하여 수신 객체 불일치(Receiver mismatch) 에러를 방지합니다.
                        withContext(Dispatchers.EDT) {
                            PsiDocumentManager.getInstance(project).commitAllDocuments()
                        }

                        val specJson = readAction {
                            if (project.isDisposed) "" else pipeline.buildSpec(pipeline.scan())
                        }

                        if (specJson != lastSpecJson.get() && specJson.isNotEmpty()) {
                            lastSpecJson.set(specJson)
                            withContext(Dispatchers.EDT) {
                                renderSpec(specJson)
                            }
                        }
                    } catch (t: Throwable) {
                        if (t !is CancellationException) {
                            LOG.error("Scan error", t)
                            withContext(Dispatchers.EDT) {
                                renderError("Error generating Swagger spec: ${t.message}")
                            }
                        }
                    }
                }
        }
    }

    private suspend fun renderSpec(specJson: String) {
        if (disposed || browser == null || project.isDisposed) return

        val assetsDir = withContext(Dispatchers.IO) {
            SwaggerAssetsExtractor.ensureExtracted()
        }
        val baseUrl = "${assetsDir.toPath().resolve("index.html").toUri()}?ts=${System.currentTimeMillis()}"

        browser.loadHTML(SwaggerPreviewHtmlBuilder.buildPreviewHtml(specJson, assetsDir), baseUrl)
        setStatus("Rendered successfully")
    }

    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) statusLabel.text = text
        }, ModalityState.any())
    }

    private fun renderError(message: String) {
        if (!disposed && browser != null && !project.isDisposed) {
            browser.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(message))
            setStatus("Error")
        }
    }

    override fun dispose() {
        disposed = true
        panelJob.cancel()
    }
}