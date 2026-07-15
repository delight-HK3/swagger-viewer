package com.github.swaggerViewer.model.swagger

/**
 * Represents a single @ApiResponse.
 * Keys in headers/links maps are the header name and link name as defined in the OpenAPI spec.
 */
data class ApiResponse(
    val description: String,
    /** Simple class name extracted from @Content(schema = @Schema(implementation = Foo.class)) */
    val contentSchema: String? = null,
    val headers: Map<String, Header> = emptyMap(),
    val links: Map<String, Link> = emptyMap()
)
