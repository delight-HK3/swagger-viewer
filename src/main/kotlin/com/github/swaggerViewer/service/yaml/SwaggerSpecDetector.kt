package com.github.swaggerViewer.service.yaml

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Checks whether a file has a yaml/yml/json extension and contains the "openapi:" or "swagger:" (YAML)
 * or "\"openapi\"" / "\"swagger\"" (JSON) key near the beginning of the file.
 * Only the first few KB are scanned instead of parsing the entire file.
 */
object SwaggerSpecDetector {

    private val SUPPORTED_EXTENSIONS = setOf("yaml", "yml", "json")
    private const val PEEK_BYTES = 8192 // Only the first 8KB of the document is inspected

    // Returns true if the file has a yaml/yml/json extension and contains an openapi/swagger version key near the top.
    fun isSwaggerOrOpenApiFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension?.lowercase() ?: return false
        if (ext !in SUPPORTED_EXTENSIONS) return false

        val text = peekText(file) ?: return false
        return containsSpecMarker(text)
    }

    // Reads the first PEEK_BYTES characters of the file without disk I/O when possible.
    // Uses the in-memory Document (safe on EDT) when the file is already open in an editor;
    // falls back to VirtualFile.inputStream only on background threads where slow I/O is allowed.
    // The inputStream path is used by hasSwaggerYamlFiles() in SwaggerViewerToolWindowFactory
    // which runs on a pooled thread, so that fallback is intentional.
    private fun peekText(file: VirtualFile): String? {
        val cached = FileDocumentManager.getInstance().getCachedDocument(file)
        if (cached != null) {
            val len = minOf(cached.textLength, PEEK_BYTES)
            return cached.charsSequence.subSequence(0, len).toString()
        }
        return try {
            val length = minOf(file.length, PEEK_BYTES.toLong()).toInt()
            if (length <= 0) return null
            val bytes = ByteArray(length)
            file.inputStream.use { it.read(bytes) }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // Checks for openapi:/swagger: (YAML) or "openapi":/"swagger": (JSON) at the start of the text.
    private fun containsSpecMarker(text: String): Boolean {
        val yamlOpenApi = Regex("(?m)^\\s*openapi\\s*:\\s*['\"]?\\d")
        val yamlSwagger = Regex("(?m)^\\s*swagger\\s*:\\s*['\"]?\\d")
        val jsonOpenApi = Regex("\"openapi\"\\s*:\\s*\"\\d")
        val jsonSwagger = Regex("\"swagger\"\\s*:\\s*\"\\d")
        return yamlOpenApi.containsMatchIn(text) ||
            yamlSwagger.containsMatchIn(text) ||
            jsonOpenApi.containsMatchIn(text) ||
            jsonSwagger.containsMatchIn(text)
    }
}
