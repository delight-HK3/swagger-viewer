package com.example.swaggerViewer.view

import com.example.swaggerViewer.service.SwaggerAssetsExtractor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * YAML/JSON 스펙 파일의 분할 에디터 우측 미리보기 패널.
 * DocumentListener로 타이핑 즉시 반응하며, 변환/파싱은 POOLED_THREAD에서,
 * browser.loadHTML()만 EDT에서 실행해 EDT 블로킹을 방지한다.
 */
class SwaggerPreviewFileEditor(
    private val project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor, Disposable {

    private val jcefSupported = JBCefApp.isSupported()
    private val browser: JBCefBrowser? = if (jcefSupported) JBCefBrowser() else null
    private val fallbackComponent: JComponent? =
        if (!jcefSupported) JLabel(
            "이 환경에서는 JCEF(내장 브라우저)를 사용할 수 없습니다.",
            SwingConstants.CENTER
        ) else null

    // POOLED_THREAD: 변환 작업이 EDT를 블로킹하지 않도록 함
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleUpdate()
        }
    }

    init {
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(documentListener)
        scheduleUpdate(immediate = true)
    }

    private fun scheduleUpdate(immediate: Boolean = false) {
        if (browser == null) return
        alarm.cancelAllRequests()
        alarm.addRequest({ updatePreview() }, if (immediate) 0 else 300)
    }

    private fun updatePreview() {
        val browser = this.browser ?: return

        // 문서 텍스트를 ReadAction으로 안전하게 읽음 (백그라운드 스레드에서 호출됨)
        val rawText = ApplicationManager.getApplication().runReadAction<String?> {
            FileDocumentManager.getInstance().getDocument(file)?.text
        } ?: return

        val specJson: String = try {
            convertToJson(rawText)
        } catch (e: Exception) {
            ApplicationManager.getApplication().invokeLater {
                browser.loadHTML(buildErrorHtml(e.message ?: "스펙을 파싱할 수 없습니다."))
            }
            return
        }

        val assetsDir = SwaggerAssetsExtractor.ensureExtracted()
        val html = buildPreviewHtml(specJson)
        val baseUrl = "file://${assetsDir.absolutePath}/index.html"
        // 렌더링만 EDT에서 실행
        ApplicationManager.getApplication().invokeLater {
            browser.loadHTML(html, baseUrl)
        }
    }

    private fun convertToJson(rawText: String): String {
        val ext = file.extension?.lowercase()
        val node = if (ext == "yaml" || ext == "yml") {
            yamlMapper.readTree(rawText)
        } else {
            jsonMapper.readTree(rawText)
        }
        return jsonMapper.writeValueAsString(node)
    }

    private fun buildPreviewHtml(specJson: String): String {
        // specJson은 이미 JSON 문자열이므로 JS 객체 리터럴 자리에 그대로 삽입
        return """
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
                        var spec = $specJson;
                        window.ui = SwaggerUIBundle({
                            spec: spec,
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
    }

    private fun buildErrorHtml(message: String): String {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 16px; color: #b00020;">
                <h3>미리보기를 표시할 수 없습니다</h3>
                <pre style="white-space: pre-wrap;">$escaped</pre>
            </body>
            </html>
        """.trimIndent()
    }

    override fun getComponent(): JComponent = browser?.component ?: fallbackComponent!!
    override fun getPreferredFocusedComponent(): JComponent? = component
    override fun getName(): String = "Swagger Viewer"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        FileDocumentManager.getInstance().getDocument(file)?.removeDocumentListener(documentListener)
        browser?.dispose()
    }
}
