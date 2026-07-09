package com.example.swaggerViewer.model.swagger

/** @Link 어노테이션에서 추출. ApiResponseInfo.links 맵의 값으로 사용된다. */
data class Link(
    val description: String? = null,
    val operationId: String? = null,
    /** @LinkParameter(name, expression) 목록 → paramName to runtimeExpression */
    val parameters: Map<String, String> = emptyMap()
)
