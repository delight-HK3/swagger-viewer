package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.annotation.SwaggerPreviewPipeline
import com.github.swaggerViewer.service.common.SwaggerAssetsExtractor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.jvm.Throws

/**
 * Live preview panel inside the Tool Window.
 * Renders by parsing @RestController/@Controller annotations through PSI static analysis only.
 * Reacts while typing: detects document changes via EditorFactory's DocumentListener (not a file-save event).
 * PSI parsing runs on a background thread via [ReadAction.nonBlocking]; only rendering runs on the EDT (no EDT blocking).
 */
class SwaggerViewerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

    /**
     *  EDT so [doRefresh] runs on EDT, which is required for [PsiDocumentManager.commitAllDocuments]
     *  and for submitting NonBlockingReadAction from the correct threading context.
     *
     *  Bound to this panel's Disposable lifecycle: [dispose] calls renderPreviewScope.cancel(),
     *  so pending refreshes don't fire after the panel/browser has already been torn down.
     */
    private val renderPreviewScope = CoroutineScope(Dispatchers.EDT + SupervisorJob())
    private var renderPreviewJob: Job? = null

    private val pipeline = SwaggerPreviewPipeline(project)

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

    /**
     * Assembles the Tool Window panel UI.
     *
     * * NORTH: toolbar consisting of a refresh button and a scan status label.
     * * CENTER: [JBCefBrowser] if JCEF is supported, otherwise a fallback message label.
     */
    private fun buildUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val refreshBtn = JButton("Refresh").apply {
                addActionListener { scheduleRefresh(immediate = true) }
            }
            add(refreshBtn)
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)

        this.browser
            ?.let { add(it.component, BorderLayout.CENTER) }
            ?: run {
                add(JBLabel(
                    "JCEF is not supported, so the preview cannot be displayed.",
                    SwingConstants.CENTER
                ), BorderLayout.CENTER)
            }
    }

    /**
     * Reacts immediately while typing: detects
     * .java/.kt file modifications via document change events (not save)
     **/
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

    /**
     * Also detects structural changes like file create/delete/rename
     * (VFS events instead of DocumentListener)
     */
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

    /**
     * Checks whether the file belongs to this project
     * and is a Java/Kotlin source file
     *
     * @param event the VFS event to check
     */
    private fun isRelevantFileEvent(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        return isProjectSourceFile(file)
    }

    /**
     * Determines whether the file is a Java/Kotlin source file
     * belonging to this project
     *
     * @param file the file to check
     */
    private fun isProjectSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        if (ext != "java" && ext != "kt") return false
        val basePath = project.basePath ?: return false
        return file.path.startsWith(basePath)
    }

    /**
     * Schedules a preview refresh.
     *
     * When called while typing, applies a debounce (500ms) to avoid a full reparse on every keystroke.
     * If immediate=true (button click / initial load), invalidates the cache and runs after 100ms.
     * Duplicate requests are cleared via renderPreviewJob.cancel() so only the latest request ever runs.
     *
     * @param immediate is refresh render preview ASAP?
     * - True: refresh preview ASAP
     * - False: refresh preview Lazy
     */
    private fun scheduleRefresh(immediate: Boolean = false) {
        this.renderPreviewJob?.cancel()
        this.renderPreviewJob = renderPreviewScope.launch {
            // A forced refresh (button click) invalidates the cache to guarantee a re-render
            if (immediate) lastSpecJson = null

            // Debounce: delay execution so a full reparse doesn't happen on every keystroke
            val delayMillis = if (immediate) 100L else 500L
            delay(timeMillis = delayMillis)

            doRefresh()
        }
    }

    /**
     * Runs on EDT ([renderPreviewScope]'s [Dispatchers.EDT]).
     * Commits all open documents first so PSI reflects the latest edits, then submits
     * a NonBlockingReadAction from the EDT — the recommended submission context in the
     * IntelliJ Platform (submitting from a pooled thread causes withDocumentsCommitted
     * to lose the correct modality state and silently never fire).
     */
    private fun doRefresh() {
        if (browser == null || project.isDisposed) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        ReadAction.nonBlocking<Unit> { performScan() }
            .expireWhen { project.isDisposed }
            .inSmartMode(project)
            .coalesceBy(this)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Annotation parsing → JSON conversion → rendering.
     * Must be called within a ReadAction context.
     *
     * @throws ProcessCanceledException if the enclosing [ReadAction.nonBlocking] is cancelled
     *   mid-scan (e.g. a write action starts or the project is disposed); rethrown as-is so the
     *   platform can retry the scan instead of treating it as a real failure.
     */
    @Throws(ProcessCanceledException::class)
    private fun performScan() {
        runCatching {
            val result = pipeline.scan()
            if (result.paths.isEmpty()) {
                // Reset the cache so a later non-empty spec that happens to serialize identically
                // to the one shown before this empty state is still treated as a change and rendered.
                lastSpecJson = null
                setStatus("No @RestController endpoints found")
                renderEmpty()
                return
            }
            val specJson = pipeline.buildSpec(result)
            if (specJson != lastSpecJson) {
                lastSpecJson = specJson
                setStatus("Static analysis — ${result.paths.size} paths")
                renderSpec(specJson)
            }

        }.onFailure { e ->
            if (e is ProcessCanceledException) { throw e }
            else {
                setStatus("Error: ${e.message?.take(80)}")
                renderError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Updates the status label text at the top of the toolbar.
     * Runs on the EDT since it's a UI operation.
     *
     * @param text the status text to display
     */
    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel.text = text }
    }

    /**
     * Renders the Swagger UI in the browser once parsing succeeds.
     * baseUrl must be set so local asset files
     * like swagger-ui.css can be loaded via relative paths.
     *
     * @param specJson the OpenAPI spec, serialized as JSON
     */
    private fun renderSpec(specJson: String) {
        ApplicationManager.getApplication().invokeLater {
            val assetsDir = SwaggerAssetsExtractor.ensureExtracted()
            // Build a platform-independent file:// URL with File.toURI() — avoids Windows backslash/double-slash issues.
            // A cache-busting query suffix is required: JBCefBrowserBase.loadUrlImpl() silently no-ops
            // loadHTML() when the url string is identical to the previously loaded one, which a fixed
            // file:// baseUrl always is across renders — the browser would keep showing stale content
            // even though fresh HTML was passed in. The suffix doesn't affect relative asset resolution.
            val baseUrl = assetsDir.toPath().resolve("index.html").toUri().toString() + "?t=${System.nanoTime()}"
            browser?.loadHTML(SwaggerPreviewHtmlBuilder.buildPreviewHtml(specJson), baseUrl)
        }
    }

    /**
     * Shows a message when no `@RestController` endpoints are found.
     */
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

    /**
     * Shows an error message in the browser
     * when an exception occurs during parsing.
     *
     * @param message to display error message
     */
    private fun renderError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            browser?.loadHTML(SwaggerPreviewHtmlBuilder.buildErrorHtml(message))
        }
    }

    /**
     * Releases the JCEF browser resource when the panel closes.
     *
     * Listener cleanup is handled automatically via the Disposable link.
     * Cancels the coroutine scope first so no pending/in-flight refresh touches the browser
     * after it's disposed.
     */
    override fun dispose() {
        renderPreviewScope.cancel()
        browser?.dispose()
    }
}
