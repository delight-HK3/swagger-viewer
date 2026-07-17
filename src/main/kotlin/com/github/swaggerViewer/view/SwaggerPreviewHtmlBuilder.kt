package com.github.swaggerViewer.view

import java.io.File
import java.util.Base64

internal object SwaggerPreviewHtmlBuilder {

    /**
     * 로컬 에셋의 절대 경로 주소를 정적 파일(CSS, JS) 경로로 매핑하여 완성형 HTML을 빌드합니다.
     */
    fun buildPreviewHtml(specJson: String, assetsDir: File): String {
        val cssUri = assetsDir.toPath().resolve("swagger-ui.css").toUri().toString()
        val jsBundleUri = assetsDir.toPath().resolve("swagger-ui-bundle.js").toUri().toString()
        val jsPresetUri = assetsDir.toPath().resolve("swagger-ui-standalone-preset.js").toUri().toString()

        // Base64로 안전하게 인코딩하여 주입 (특수문자 및 백틱 깨짐 100% 방지)
        val base64Spec = Base64.getEncoder().encodeToString(specJson.toByteArray(Charsets.UTF_8))

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Swagger UI Live Preview</title>
                <link rel="stylesheet" type="text/css" href="$cssUri" />
                <style>
                    html { box-sizing: border-box; overflow-y: scroll; }
                    *, *:before, *:after { box-sizing: inherit; }
                    body { margin: 0; background: #fafafa; }
                    
                    /* [수정] JCEF(Chromium) 스펙에 맞게 로딩 텍스트가 정확히 노출되도록 가상 요소로 변경 */
                    #swagger-ui:empty::before {
                        content: "Loading Swagger UI...";
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        font-family: sans-serif;
                        color: #999;
                        font-size: 14px;
                    }
                </style>
            </head>
            <body>
                <div id="swagger-ui"></div>
                
                <script src="$jsBundleUri"></script>
                <script src="$jsPresetUri"></script>
                <script>
                    // Base64 디코딩 및 UTF-8 복원 함수 (한글 깨짐 방지)
                    function decodeBase64Utf8(base64Str) {
                        const binaryStr = atob(base64Str);
                        const bytes = new Uint8Array(binaryStr.length);
                        for (let i = 0; i < binaryStr.length; i++) {
                            bytes[i] = binaryStr.charCodeAt(i);
                        }
                        return new TextDecoder("utf-8").decode(bytes);
                    }

                    // 향후 JCEF에서 혹시 부분 업데이트(executeJavaScript)를 보완용으로 쓸 경우를 위해 유지
                    window.updateSwaggerSpec = function(newJsonStr) {
                        try {
                            const specObj = typeof newJsonStr === 'string' ? JSON.parse(newJsonStr) : newJsonStr;
                            if (window.ui) {
                                window.ui.specActions.updateSpec(specObj);
                                window.ui.layoutActions.show('BaseLayout', true);
                            } else {
                                initSwaggerUI(specObj);
                            }
                        } catch (e) {
                            console.error("Failed to update Swagger spec dynamically:", e);
                        }
                    };

                    function initSwaggerUI(specData) {
                        try {
                            window.ui = SwaggerUIBundle({
                                spec: specData,
                                dom_id: '#swagger-ui',
                                deepLinking: true,
                                presets: [
                                    SwaggerUIBundle.presets.apis,
                                    SwaggerUIStandalonePreset
                                ],
                                plugins: [
                                    SwaggerUIBundle.plugins.DownloadUrl
                                ],
                                layout: "BaseLayout"
                            });
                        } catch (e) {
                            console.error("Failed to initialize Swagger UI:", e);
                            document.getElementById('swagger-ui').innerHTML = 
                                "<div style='color:red; padding: 20px;'><h3>Swagger UI Initialization Error</h3><pre>" + e.toString() + "</pre></div>";
                        }
                    }

                    // [수정] 이미지나 외부 리소스 대기를 안 하는 DOMContentLoaded를 사용하여 초기화 속도 극대화
                    document.addEventListener("DOMContentLoaded", function() {
                        try {
                            const rawJson = decodeBase64Utf8("$base64Spec");
                            const initialSpec = JSON.parse(rawJson);
                            initSwaggerUI(initialSpec);
                        } catch (e) {
                            console.error("Failed to parse initial Swagger JSON:", e);
                            document.getElementById('swagger-ui').innerHTML = 
                                "<div style='color:red; padding: 20px;'><h3>JSON Parsing Error</h3><pre>" + e.toString() + "</pre></div>";
                        }
                    });
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    fun buildErrorHtml(message: String): String {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 16px; color: #b00020; background: #fafafa;">
                <h3>Preview Error</h3>
                <pre style="white-space: pre-wrap;">$escaped</pre>
            </body>
            </html>
        """.trimIndent()
    }

    fun buildInfoHtml(title: String, detail: String): String {
        val t = title.replace("&", "&amp;").replace("<", "&lt;")
        val d = detail.replace("&", "&amp;").replace("<", "&lt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 32px; color: #555; background: #fafafa;">
                <h3>$t</h3>
                <p>$d</p>
            </body>
            </html>
        """.trimIndent()
    }
}