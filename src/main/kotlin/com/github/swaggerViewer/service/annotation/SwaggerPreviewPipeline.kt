package com.github.swaggerViewer.service.annotation

import com.github.swaggerViewer.model.ScanResult
import com.intellij.openapi.project.Project

/**
 * Entry point for the annotation-based Swagger preview pipeline.
 *
 * Pipeline stages:
 *   1. [SwaggerAnnotationScanner]    — PSI 트리에서 Spring MVC + Swagger 어노테이션 수집 → [com.github.swaggerViewer.model.ScanResult]
 *   2. [PsiSchemaAnalyzer]           — 참조된 클래스 필드 타입 분석 (Scanner 내부에서 호출)
 *   3. [SwaggerAnnotationSerializer] — [com.github.swaggerViewer.model.ScanResult] → OpenAPI 3.0.0 JSON 직렬화
 *
 * The [View layer][com.github.swaggerViewer.view.SwaggerViewerPanel] calls [scan] and [buildSpec]
 * separately so it can inspect path count between the two steps (e.g. to show an empty-state message).
 */
class SwaggerPreviewPipeline(project: Project) {

    private val scanner = SwaggerAnnotationScanner(project)
    private val serializer = SwaggerAnnotationSerializer()

    fun scan(): ScanResult = scanner.scan()

    fun buildSpec(result: ScanResult): String = serializer.build(result)
}