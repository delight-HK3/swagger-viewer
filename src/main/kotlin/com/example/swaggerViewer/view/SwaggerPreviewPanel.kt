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
 * Tool Window 내 실시간 미리보기 패널.
 * PSI 정적 분석만으로 @RestController/@Controller 어노테이션을 파싱해 렌더링한다.
 * 타이핑 중 반응: EditorFactory DocumentListener로 문서 변경을 감지 (파일 저장 이벤트가 아님).
 * PSI 파싱은 POOLED_THREAD + ReadAction, 렌더링만 EDT에서 실행 (EDT 블로킹 금지).
 */
class SwaggerPreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val scanner = PsiSwaggerScanner(project)
    private val specBuilder = OpenApiSpecBuilder()

    private val statusLabel = JBLabel("스캔 중...", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }
    @Volatile
    private var lastSpecJson: String? = null

    init {
        buildUi()
        setupDocumentListener()
        setupFileWatcher()
        scheduleRefresh(immediate = true)
    }

    private fun buildUi() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val refreshBtn = JButton("새로고침").apply {
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
                JBLabel("JCEF를 사용할 수 없어 미리보기를 표시할 수 없습니다.", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        }
    }

    // 타이핑 즉시 반응: 문서 변경 이벤트(저장 아님)로 .java/.kt 파일 수정을 감지
    private fun setupDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed) return
                    val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    val ext = vFile.extension?.lowercase()
                    if (ext != "java" && ext != "kt") return
                    // 이 프로젝트에 속하는 파일만 처리
                    val basePath = project.basePath ?: return
                    if (!vFile.path.startsWith(basePath)) return
                    scheduleRefresh()
                }
            },
            this
        )
    }

    // 파일 생성/삭제/이름변경 등 구조적 변경도 감지 (DocumentListener 대신 VFS 이벤트)
    private fun setupFileWatcher() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any {
                        val ext = it.file?.extension?.lowercase()
                        ext == "java" || ext == "kt"
                    }
                    if (relevant) scheduleRefresh()
                }
            }
        )
    }

    fun scheduleRefresh(immediate: Boolean = false) {
        alarm.cancelAllRequests()
        // 강제 새로고침(버튼 클릭)은 캐시를 무효화해 반드시 재렌더링
        if (immediate) lastSpecJson = null
        // 디바운스: 키 입력마다 전체 재파싱하지 않도록 지연 후 실행
        alarm.addRequest({ doRefresh() }, if (immediate) 100 else 500)
    }

    private fun doRefresh() {
        if (browser == null) return

        // 인덱싱 완료 후 실행, PSI는 ReadAction 내에서만 접근
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().runReadAction {
                try {
                    val result = scanner.scan()
                    if (result.paths.isEmpty()) {
                        setStatus("@RestController 엔드포인트를 찾을 수 없습니다")
                        renderEmpty()
                        return@runReadAction
                    }
                    val specJson = specBuilder.build(result)
                    if (specJson != lastSpecJson) {
                        lastSpecJson = specJson
                        setStatus("정적 분석 — ${result.paths.size}개 경로")
                        renderSpec(specJson)
                    }
                } catch (e: Exception) {
                    setStatus("오류: ${e.message?.take(80)}")
                    renderError(e.message ?: "알 수 없는 오류")
                }
            }
        }
    }

    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel.text = text }
    }

    private fun renderSpec(specJson: String) {
        ApplicationManager.getApplication().invokeLater {
            val assetsDir = SwaggerAssetsExtractor.ensureExtracted()
            // File.toURI()로 플랫폼 독립적 file:// URL 생성 — Windows 백슬래시/슬래시 2개 문제 방지
            val baseUrl = assetsDir.toPath().resolve("index.html").toUri().toString()
            browser?.loadHTML(buildPreviewHtml(specJson), baseUrl)
        }
    }

    private fun renderEmpty() {
        ApplicationManager.getApplication().invokeLater {
            browser?.loadHTML(
                buildInfoHtml(
                    "Spring Boot 엔드포인트를 찾을 수 없습니다",
                    "@RestController 또는 @Controller 클래스와 HTTP 매핑 어노테이션이 있는지 확인하세요."
                )
            )
        }
    }

    private fun renderError(message: String) {
        ApplicationManager.getApplication().invokeLater { browser?.loadHTML(buildErrorHtml(message)) }
    }

    private fun buildPreviewHtml(specJson: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Swagger Preview</title>
            <link rel="stylesheet" href="swagger-ui.css">
            <style>
                html, body { margin: 0; padding: 0; height: 100%; background: #ffffff; }
                #swagger-ui { height: 100%; }
            </style>
        </head>
        <body>
            <div id="swagger-ui"></div>
            <script src="swagger-ui-bundle.js"></script>
            <script src="swagger-ui-standalone-preset.js"></script>
            <script>
                window.onload = function() {
                    window.ui = SwaggerUIBundle({
                        spec: $specJson,
                        dom_id: '#swagger-ui',
                        presets: [
                            SwaggerUIBundle.presets.apis,
                            SwaggerUIStandalonePreset
                        ],
                        layout: "StandaloneLayout",
                        deepLinking: false
                    });
                };
            </script>
        </body>
        </html>
    """.trimIndent()

    private fun buildInfoHtml(title: String, detail: String): String {
        val t = title.replace("&", "&amp;").replace("<", "&lt;")
        val d = detail.replace("&", "&amp;").replace("<", "&lt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 32px; color: #555;">
                <h3>$t</h3>
                <p>$d</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildErrorHtml(message: String): String {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 16px; color: #b00020;">
                <h3>미리보기 오류</h3>
                <pre style="white-space: pre-wrap;">$escaped</pre>
            </body>
            </html>
        """.trimIndent()
    }

    override fun dispose() {
        browser?.dispose()
    }
}
