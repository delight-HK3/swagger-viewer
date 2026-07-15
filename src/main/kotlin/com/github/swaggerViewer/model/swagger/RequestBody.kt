package com.github.swaggerViewer.model.swagger

/**
 * Request body info extracted from @Operation.requestBody or Spring @RequestBody.
 * Serialized as a $ref if contentSchema is set, otherwise as a generic object.
 */
data class RequestBody(
    val description: String? = null,
    val required: Boolean = true,
    /** Simple class name extracted from @Schema(implementation = Foo.class) */
    val contentSchema: String? = null
)
