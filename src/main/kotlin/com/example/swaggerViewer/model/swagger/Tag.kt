package com.example.swaggerViewer.model.swagger

/**
 * OpenAPI Tag 하나를 표현한다.
 *
 * 생산: [com.example.swaggerViewer.service.PsiSwaggerScanner]
 *   - @Tag(name = "...", description = "...", externalDocs = @ExternalDocumentation(...)) 에서 추출된다.
 *
 * 소비: [com.example.swaggerViewer.service.OpenApiSpecBuilder]
 *   - OpenAPI 3.0 최상위 "tags" 배열의 항목으로 직렬화된다.
 */
data class Tag(
    val name: String,
    val description: String?,
    val externalDocs: ExternalDocumentation? = null
)
