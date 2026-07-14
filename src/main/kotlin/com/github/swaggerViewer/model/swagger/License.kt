package com.github.swaggerViewer.model.swagger

/** Represents @License inside @Info. name is required per OAS 3.0. */
data class License(
    val name: String,
    val url: String? = null
)
