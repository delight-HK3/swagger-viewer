package com.example.swaggerViewer.model.swagger

/**
 * HTTP 엔드포인트의 파라미터 하나를 표현한다.
 *
 * 생산: [com.example.swaggerViewer.service.PsiSwaggerScanner]
 *   - @PathVariable  → location = "path",  required = true
 *   - @RequestParam  → location = "query", required = @RequestParam.required 값
 *   - @Parameter     → description / example / schema 보강, 또는 독립 파라미터 추가
 *   - @Operation.parameters / @Parameters → 메서드 파라미터에 없는 추가 파라미터
 *
 * 소비: [com.example.swaggerViewer.service.OpenApiSpecBuilder]
 *   - OpenAPI 3.0 parameters 배열의 각 항목으로 직렬화된다.
 */
data class Parameter(
    val name: String,
    /** OpenAPI "in" 필드값. "path" / "query" / "header" / "cookie" */
    val location: String,
    val description: String?,
    val required: Boolean,
    val example: String? = null,
    val schema: Schema? = null
)
