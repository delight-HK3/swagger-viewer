package com.github.swaggerViewer.model.swagger

/**
 * Represents a single @SecurityScheme entry in components/securitySchemes.
 * Named "Oas" to avoid clashing with the io.swagger annotation class of the same name.
 */
data class OasSecurityScheme(
    /** OAS type string: "http", "apiKey", "oauth2", "openIdConnect" */
    val type: String,
    val description: String? = null,
    /** For type=http: "bearer", "basic", etc. */
    val scheme: String? = null,
    /** For type=http + scheme=bearer: token format hint, e.g. "JWT". */
    val bearerFormat: String? = null,
    /** For type=apiKey: the parameter name in the request (header/query/cookie). */
    val paramName: String? = null,
    /** For type=apiKey: "header", "query", or "cookie". */
    val location: String? = null
)
