package com.github.swaggerViewer.model.swagger

/**
 * Extracted from @Callback.
 * OpenAPI spec shape: callbacks.[name].[expression].[method] = { operation object }
 *
 * @property expression  Runtime URL expression from callbackUrlExpression (e.g. "{$request.query.callbackUrl}")
 * @property method      HTTP verb from @Operation.method inside the @Callback (lowercase, e.g. "post")
 * @property operation   The nested @Operation describing the callback request/response
 */
data class Callback(
    val expression: String,
    val method: String,
    val operation: Operation
)
