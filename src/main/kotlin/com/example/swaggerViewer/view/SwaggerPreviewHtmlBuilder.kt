package com.example.swaggerViewer.view

// Swagger UI HTML builder invoked from the panel
internal object SwaggerPreviewHtmlBuilder {

    fun buildPreviewHtml(specJson: String): String = """
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

    fun buildErrorHtml(message: String): String {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; padding: 16px; color: #b00020;">
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
            <body style="font-family: sans-serif; padding: 32px; color: #555;">
                <h3>$t</h3>
                <p>$d</p>
            </body>
            </html>
        """.trimIndent()
    }
}
