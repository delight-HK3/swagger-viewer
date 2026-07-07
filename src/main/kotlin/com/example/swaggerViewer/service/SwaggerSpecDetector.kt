package com.example.swaggerViewer.service

import com.intellij.openapi.vfs.VirtualFile

/**
 * 파일 확장자가 yaml/yml/json 이면서, 파일 앞부분에 "openapi:" 또는 "swagger:" (YAML)
 * 혹은 "\"openapi\"" / "\"swagger\"" (JSON) 키가 포함되어 있는지 검사한다.
 * 전체 파일을 파싱하지 않고 앞부분 몇 KB만 훑어 가볍게 판별한다.
 */
object SwaggerSpecDetector {

    private val SUPPORTED_EXTENSIONS = setOf("yaml", "yml", "json")
    private const val PEEK_BYTES = 8192 // 문서 앞의 8KB만 판별

    fun isSwaggerOrOpenApiFile(file: VirtualFile): Boolean {
        // 폴더인 경우 false
        if (file.isDirectory) return false
        // 확장자가 null인 경우 false
        val ext = file.extension?.lowercase() ?: return false
        // SUPPORTED_EXTENSIONS 포함되어있지 않은 경우 false
        if (ext !in SUPPORTED_EXTENSIONS) return false

        return try {
            // 파일을 최대 8KB까지만 읽기
            val length = minOf(file.length, PEEK_BYTES.toLong()).toInt()
            if (length <= 0) return false
            val bytes = ByteArray(length)
            file.inputStream.use { it.read(bytes) }
            val text = String(bytes, Charsets.UTF_8)
            containsSpecMarker(text)
        } catch (e: Exception) {
            false
        }
    }

    // 정규식으로
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
