package com.example.swaggerViewer.view

import com.example.swaggerViewer.service.OpenApiSpecBuilder
import com.example.swaggerViewer.service.PsiSwaggerScanner
import com.example.swaggerViewer.service.SwaggerAssetsExtractor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Live preview panel inside the Tool Window.
 * Renders by parsing @RestController/@Controller annotations through PSI static analysis only.
 * Reacts while typing: detects document changes via EditorFactory's DocumentListener (not a file-save event).
 * PSI parsing runs on POOLED_THREAD + ReadAction; only rendering runs on the EDT (no EDT blocking).
 */
class SwaggerViewerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val scanner = PsiSwaggerScanner(project)
    private val specBuilder = OpenApiSpecBuilder()

    private val statusLabel = JBLabel("Scanning...", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }

    @Volatile
    private var lastSpecJson: String? = null

    // Initial plugin setup
    init {
        buildUi() // Assemble the UI
        setupDocumentListener() // Typing listener
        setupFileWatcher() // File rename/create/delete
        scheduleRefresh(immediate = true)
    }

    // Assembles the Tool Window panel UI.
    // NORTH: toolbar consisting of a refresh button and a scan status label.
    // CENTER: JBCefBrowser if JCEF is supported, otherwise a fallback message label.
    private fun buildUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val refreshBtn = JButton("Refresh").apply {
                addActionListener { scheduleRefresh(immediate = true) }
            }
            add(refreshBtn)
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)

        if (browser != null) {
            add(browser.component, BorderLayout.CENTER)
        } else {
            add(
                JBLabel("JCEF is not supported, so the preview cannot be displayed.", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        }
    }

    // Reacts immediately while typing: detects .java/.kt file modifications via document change events (not save)
    private fun setupDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed) return
                    val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isProjectSourceFile(vFile)) return
                    scheduleRefresh()
                }
            },
            this // Removed once this listener's work is done
        )
    }

    // Also detects structural changes like file create/delete/rename (VFS events instead of DocumentListener)
    private fun setupFileWatcher() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { isRelevantFileEvent(it) }) scheduleRefresh()
                }
            }
        )
    }

    // Checks whether the file belongs to this project and is a Java/Kotlin source file
    private fun isRelevantFileEvent(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        return isProjectSourceFile(file)
    }

    // Determines whether the file is a Java/Kotlin source file belonging to this project
    private fun isProjectSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        if (ext != "java" && ext != "kt") return false
        val basePath = project.basePath ?: return false
        return file.path.startsWith(basePath)
    }

    // Schedules a preview refresh.
    // When called while typing, applies a debounce (500ms) to avoid a full reparse on every keystroke.
    // If immediate=true (button click / initial load), invalidates the cache and runs after 100ms.
    // Duplicate requests are cleared via Alarm.cancelAllRequests() so only the latest request ever runs.
    private fun scheduleRefresh(immediate: Boolean = false) {
        alarm.cancelAllRequests()
        // A forced refresh (button click) invalidates the cache to guarantee a re-render
        if (immediate) lastSpecJson = null
        // Debounce: delay execution so a full reparse doesn't happen on every keystroke
        alarm.addRequest({ doRefresh() }, if (immediate) 100 else 500)
    }

    // Runs performScan() inside a ReadAction after indexing completes
    private fun doRefresh() {
        if (browser == null) return
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().runReadAction { performScan() }
        }
    }

    // Annotation parsing → JSON conversion → rendering. Must be called within a ReadAction context.
    private fun performScan() {
        try {
            val result = scanner.scan()
            if (result.paths.isEmpty()) {
                setStatus("No @RestController endpoints found")
                renderEmpty()
                return
            }
            val specJson = specBuilder.build(result)
            if (specJson != lastSpecJson) {
                lastSpecJson = specJson
                setStatus("Static analysis — ${result.paths.size} paths")
                renderSpec(specJson)
            }
        } catch (e: Exception) {
            setStatus("Error: ${e.message?.take(80)}")
            renderError(e.message ?: "Unknown error")
        }
    }

    // Updates the status label text at the top of the toolbar. Runs on the EDT since it's a UI operation.
    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel.text = text }
    }

    // Renders the Swagger UI in the browser once parsing succeeds.
    // baseUrl must be set so local asset files like swagger-ui.css can be loaded via relative paths.
    private fun renderSpec(specJson: String) {
        ApplicationManager.getApplication().invokeLater {
            val assetsDir = SwaggerAssetsExtractor.ensureExtracted()
            // Build a platform-independent file:// URL with File.toURI() — avoids Windows backslash/double-slash issues
            val baseUrl = assetsDir.toPath().resolve("index.html").toUri().toString()
            browser?.loadHTML(SwaggerPreviewHtmlBuilder.buildPreviewHtml(specJson), baseUrl)
        }
    }

    // Shows a message when no @RestController endpoints are found.
    private fun renderEmpty() {
        ApplicationManager.getApplication().invokeLater {
            browser?.loadHTML(
                SwaggerPreviewHtmlBuilder.buildInfoHtml(
                    "No Spring Boot endpoints found",
                    "Check that you have a @RestController or @Controller class with HTTP mapping annotations."
                )
            )
        }
    }

    // Shows an error message in the browser when an exception occurs during parsing.
    private fun renderError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            browser?.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(message))
        }
    }

    // Releases the JCEF browser resource when the panel closes. Listener cleanup is handled automatically via the Disposable link.
    override fun dispose() {
        browser?.dispose()
    }
}
