package com.github.swaggerViewer.model.swagger

/**
 * Represents the metadata of a single HTTP endpoint (per method).
 * Parsed from @Operation, @ApiResponse(s), @Parameter(s), @Hidden, @SecurityRequirement etc.
 * Falls back to the method name if summary is absent.
 *
 * @property tags      Tag name list this operation belongs to. Inherited from the class-level @Tag.
 * @property responses responseCode → ApiResponse map. Defaults to "200" → "OK" if empty.
 * @property security  Operation-level security requirements. Inherited from class-level @SecurityRequirement if empty.
 */
data class Operation(
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val operationId: String?,
    val deprecated: Boolean = false,
    /** @Hidden or @Operation(hidden = true) — when true, hidden from Swagger UI. */
    val hidden: Boolean = false,
    val externalDocs: ExternalDocumentation? = null,
    val parameters: List<Parameter>,
    val requestBody: RequestBody? = null,
    val responses: Map<String, ApiResponse>,
    val security: List<SecurityRequirement> = emptyList(),
    /** Operation-level server list (@Operation.servers). Overrides the global server list. */
    val servers: List<Server> = emptyList(),
    /** @Callback annotations on this method. Map key = callback name. */
    val callbacks: Map<String, Callback> = emptyMap(),
    /**
     * @Extension / @ExtensionProperty annotations on @Operation.
     * Map key = extension name (guaranteed to start with "x-"), value = property map.
     */
    val extensions: Map<String, Map<String, String>> = emptyMap()
)
