package com.github.swaggerViewer.service.annotation

import com.github.swaggerViewer.model.ScanResult
import com.intellij.openapi.project.Project

/**
 * [step 03-A] Annotation pipeline facade — the single entry point that [SwaggerViewerPanel]
 * calls to drive the full annotation-based preview pipeline.
 *
 * Pipeline stages (in order):
 *  1. [scan] → [SwaggerAnnotationScanner] [step 04-A]: traverses the PSI tree and collects all
 *     Spring MVC + Swagger annotations into a [com.github.swaggerViewer.model.ScanResult].
 *     [PsiSchemaAnalyzer] [step 06-A] is invoked internally at the end of the scan.
 *  2. [buildSpec] → [SwaggerAnnotationSerializer] [step 07-A]: serializes the [ScanResult]
 *     to an OpenAPI 3.0.0 JSON string.
 *
 * The two steps are exposed as separate methods so the caller can inspect intermediate state
 * (e.g. check whether any paths were found) between them.
 *
 * Must be called inside a [com.intellij.openapi.application.ReadAction] — guaranteed by
 * [SwaggerViewerPanel] [step 02-A].
 *
 * @see SwaggerAnnotationScanner
 * @see SwaggerAnnotationSerializer
 */
class SwaggerPreviewPipeline(project: Project) {

    private val scanner = SwaggerAnnotationScanner(project)
    private val serializer = SwaggerAnnotationSerializer()

    fun scan(): ScanResult = scanner.scan()

    fun buildSpec(result: ScanResult): String = serializer.build(result)
}