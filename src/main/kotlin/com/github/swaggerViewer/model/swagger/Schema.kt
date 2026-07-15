package com.github.swaggerViewer.model.swagger

/**
 * Schema metadata extracted from @Schema.
 * If implementation is set, serialized as a $ref; otherwise serialized as an inline schema.
 */
data class Schema(
    val type: String? = null,
    val format: String? = null,
    val minimum: String? = null,
    val maximum: String? = null,
    val defaultValue: String? = null,
    val allowableValues: List<String> = emptyList(),
    /** @Schema(implementation = Foo.class) → serialized as "$ref: #/components/schemas/Foo" */
    val implementation: String? = null
)
