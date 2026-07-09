package com.example.swaggerViewer.model.swagger

/**
 * @ApiResponse 하나를 표현한다.
 * headers/links 맵의 키는 OpenAPI 스펙의 헤더명/링크명이다.
 */
data class ApiResponse(
    val description: String,
    /** @Content(schema = @Schema(implementation = Foo.class))에서 추출한 클래스 단순명 */
    val contentSchema: String? = null,
    val headers: Map<String, Header> = emptyMap(),
    val links: Map<String, Link> = emptyMap()
)
