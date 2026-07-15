package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.common.SwaggerAssetsExtractor
import com.github.swaggerViewer.service.yaml.SwaggerSpecDetector
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * YAML tab panel in the Tool Window.
 * Detects the OpenAPI YAML/JSON file active in the editor and renders a live preview.
 * Editing happens in IntelliJ's default editor; this panel is a read-only viewer only.
 */
class SwaggerViewerYamlPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()

    @Volatile
    private var currentFile: VirtualFile? = null

    init {
        buildUi()
        setupDocumentListener()
        setupFileEditorListener()
        // Render immediately if an OpenAPI file is already active when the panel opens
        FileEditorManager.getInstance(project).selectedFiles
            .firstOrNull { SwaggerSpecDetector.isSwaggerOrOpenApiFile(it) }
            ?.let { switchToFile(it) }
    }

    // Assemble the UI
    private fun buildUi() {
        if (browser != null) {
            add(browser.component, BorderLayout.CENTER)
        } else {
            add(JBLabel("JCEF is not supported, so the preview cannot be displayed.", SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }

    // Listens to all document changes globally; reacts only when the currently tracked OpenAPI file is edited.
    // Using EditorFactory.eventMulticaster is more reliable than per-file addDocumentListener because
    // the Document object is guaranteed to exist when this fires (per-file attachment can silently no-op
    // if FileDocumentManager.getDocument() returns null before the editor has loaded it).
    private fun setupDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed) return
                    val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (vFile != currentFile) return
                    scheduleUpdate()
                }
            },
            this
        )
    }

    // When the editor tab switches to an OpenAPI file, replace the tracked file with it.
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

    // Replace the tracked file. The global document listener (set up in setupDocumentListener)
    // already filters by currentFile, so switching here is sufficient.
    private fun switchToFile(file: VirtualFile) {
        currentFile = file
        scheduleUpdate(immediate = true)
    }

    // Schedules a preview refresh with a 300ms debounce. Cancels the previous request so only the latest one runs during continuous input.
    private fun scheduleUpdate(immediate: Boolean = false) {
        if (browser == null) return
        alarm.cancelAllRequests()
        alarm.addRequest({ updatePreview() }, if (immediate) 0 else 300)
    }

    // Reads the current file's text, converts it to JSON, generates Swagger UI HTML, and loads it into the browser.
    // ReadAction guarantees PSI thread safety; the UI update is delegated to the EDT.
    private fun updatePreview() {
        val browser = this.browser ?: return
        val file = currentFile ?: return

        try {
            val rawText = ApplicationManager.getApplication().runReadAction<String?> {
                FileDocumentManager.getInstance().getDocument(file)?.text
            } ?: return

            val specJson = try {
                convertToJson(rawText, file)
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(e.message ?: "Parse error"))
                }
                return
            }

            val assetsDir = SwaggerAssetsExtractor.ensureExtracted()
            val html = SwaggerPreviewHtmlBuilder.buildPreviewHtml(specJson)
            // Build a platform-independent file:// URL with File.toURI() — avoids Windows backslash/double-slash issues
            val baseUrl = assetsDir.toPath().resolve("index.html").toUri().toString()
            ApplicationManager.getApplication().invokeLater {
                browser.loadHTML(html, baseUrl)
            }
        } catch (e: Exception) {
            ApplicationManager.getApplication().invokeLater {
                browser.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(e.message ?: "Unexpected error"))
            }
        }
    }

    // Normalizes raw YAML/JSON text into a JSON string. Swagger UI only accepts JSON, so the format is determined by extension and converted.
    private fun convertToJson(rawText: String, file: VirtualFile): String {
        val ext = file.extension?.lowercase()
        val node = if (ext == "yaml" || ext == "yml") {
            yamlMapper.readTree(rawText)
        } else {
            jsonMapper.readTree(rawText)
        }
        return jsonMapper.writeValueAsString(node)
    }

    // Global document listener cleanup is handled automatically via the Disposable link in setupDocumentListener.
    override fun dispose() {
        browser?.dispose()
    }
}
