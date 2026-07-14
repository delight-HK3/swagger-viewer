package com.github.swaggerViewer.service.common

import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * Extracts swagger-ui static assets (css/js) bundled in the plugin JAR to a temp directory once and caches them.
 * JCEF cannot load classpath resources directly, so they must be unpacked to disk.
 */
object SwaggerAssetsExtractor {

    private const val ASSET_RESOURCE_DIR = "/swagger-ui-assets"
    private val ASSET_FILES = listOf(
        "swagger-ui.css",
        "swagger-ui-bundle.js",
        "swagger-ui-standalone-preset.js",
        "favicon-16x16.png",
        "favicon-32x32.png"
    )

    // Cache for the directory path extracted once
    @Volatile
    private var cachedDir: File? = null

    // Extracts all bundled swagger-ui assets to a temp directory on first call; returns the cached directory thereafter.
    @Synchronized
    fun ensureExtracted(): File {
        cachedDir?.let { if (it.exists()) return it }

        // System temp directory managed by IntelliJ
        val targetDir = File(PathManager.getSystemPath(), "swagger-viewer-plugin-assets")
        if (!targetDir.exists()) targetDir.mkdirs()

        for (fileName in ASSET_FILES) {
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0) continue
            // Open the file inside the JAR as a byte stream
            javaClass.getResourceAsStream("$ASSET_RESOURCE_DIR/$fileName")?.use { input ->
                // Copy file registered in ASSET_FILES
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        cachedDir = targetDir
        return targetDir
    }
}
