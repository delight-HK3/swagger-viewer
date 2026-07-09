package com.example.swaggerViewer.model.swagger

/** @Header 어노테이션에서 추출. ApiResponseInfo.headers 맵의 값으로 사용된다. */
data class Header(
    val description: String? = null,
    val schema: Schema? = null
)
