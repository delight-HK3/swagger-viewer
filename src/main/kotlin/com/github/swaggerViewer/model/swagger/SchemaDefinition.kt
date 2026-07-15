package com.github.swaggerViewer.model.swagger

/**
 * A fully-resolved component schema built by PSI field analysis of the referenced class.
 * Serialized into the components/schemas section of the OpenAPI spec.
 *
 * @property description Class-level @Schema(description) value.
 * @property properties  Field name → PropertySchema. Empty for enum types.
 * @property required    Field names that carry a @NotNull / @jakarta.NotNull constraint.
 * @property enumValues  Non-null for enum classes; null for object types.
 */
data class SchemaDefinition(
    val description: String? = null,
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
    val enumValues: List<String>? = null
)

/**
 * Schema of a single field inside a SchemaDefinition.
 * Exactly one of [type] or [ref] is non-null.
 * [items] is set only when [type] == "array".
 */
data class PropertySchema(
    val type: String? = null,
    val format: String? = null,
    val ref: String? = null,
    val items: PropertySchema? = null,
    val description: String? = null,
    val example: String? = null,
    val nullable: Boolean = false
)
