package com.github.swaggerViewer.model.swagger

/**
 * Represents a single parameter of an HTTP endpoint.
 *
 * Annotation-to-field mapping rules:
 *   - @PathVariable  → location = "path",  required = true
 *   - @RequestParam  → location = "query", required = @RequestParam.required value
 *   - @Parameter on method param → enriches description / example / schema of the bound param
 *   - @Operation.parameters / @Parameters → adds extra params not present as method params
 */
data class Parameter(
    val name: String,
    /** OpenAPI "in" field value. One of: "path" / "query" / "header" / "cookie" */
    val location: String,
    val description: String?,
    val required: Boolean,
    val example: String? = null,
    val schema: Schema? = null
)
