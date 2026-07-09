package com.example.swaggerViewer.service

import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * 플러그인 JAR 내부에 번들된 swagger-ui 정적 자산(css/js)을 임시 디렉토리에 1회만 추출해 캐시한다.
 * JCEF는 classpath 리소스를 직접 로드할 수 없으므로 디스크에 풀어줘야 한다.
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

    // 한 번 추출한 디렉토리 경로를 기억해두는 캐시
    @Volatile
    private var cachedDir: File? = null

    @Synchronized
    fun ensureExtracted(): File {
        cachedDir?.let { if (it.exists()) return it }

        // intellij가 관리하는 시스템 임시 폴더 경로
        val targetDir = File(PathManager.getSystemPath(), "swagger-viewer-plugin-assets")
        if (!targetDir.exists()) targetDir.mkdirs()

        for (fileName in ASSET_FILES) {
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0) continue
            // JAR 내부 파일을 바이트 스트림으로 오픈
            javaClass.getResourceAsStream("$ASSET_RESOURCE_DIR/$fileName")?.use { input ->
                // ASSET_FILES에 등록된 파일 복사
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        cachedDir = targetDir
        return targetDir
    }
}
