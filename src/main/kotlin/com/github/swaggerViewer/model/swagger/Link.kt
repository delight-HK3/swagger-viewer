package com.github.swaggerViewer.model.swagger

/** Extracted from @Link. Used as a value in ApiResponse.links. */
data class Link(
    val description: String? = null,
    val operationId: String? = null,
    /** Absolute URL or relative path reference to the linked operation (alternative to operationId). */
    val operationRef: String? = null,
    /** Optional server override for the linked operation. */
    val server: Server? = null,
    /** @LinkParameter(name, expression) list → paramName to runtimeExpression */
    val parameters: Map<String, String> = emptyMap()
)
