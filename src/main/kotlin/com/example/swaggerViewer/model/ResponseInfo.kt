package com.example.swaggerViewer.model

/**
 * @ApiResponse 하나를 표현한다. Map<String, String>이던 responses 값을 대체한다.
 * headers/links 맵의 키는 OpenAPI 스펙의 헤더명/링크명이다.
 */
data class ResponseInfo(
    val description: String,
    /** @Content(schema = @Schema(implementation = Foo.class))에서 추출한 클래스 단순명 */
    val contentSchema: String? = null,
    val headers: Map<String, HeaderInfo> = emptyMap(),
    val links: Map<String, LinkInfo> = emptyMap()
)
