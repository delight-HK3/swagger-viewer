package com.example.swaggerViewer.model

/** @Header 어노테이션에서 추출. ResponseInfo.headers 맵의 값으로 사용된다. */
data class HeaderInfo(
    val description: String? = null,
    val schema: SchemaInfo? = null
)
