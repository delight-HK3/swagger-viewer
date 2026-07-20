package com.github.swaggerViewer.service.annotation

import com.github.swaggerViewer.model.ScanResult
import com.github.swaggerViewer.model.swagger.ApiResponse
import com.github.swaggerViewer.model.swagger.Callback
import com.github.swaggerViewer.model.swagger.ExternalDocumentation
import com.github.swaggerViewer.model.swagger.Header
import com.github.swaggerViewer.model.swagger.Link
import com.github.swaggerViewer.model.swagger.OasSecurityScheme
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.Parameter
import com.github.swaggerViewer.model.swagger.PropertySchema
import com.github.swaggerViewer.model.swagger.RequestBody
import com.github.swaggerViewer.model.swagger.Schema
import com.github.swaggerViewer.model.swagger.SchemaDefinition
import com.github.swaggerViewer.model.swagger.SecurityRequirement
import com.github.swaggerViewer.model.swagger.Server
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * [step 07-A] Serializer — converts a [ScanResult] produced by [SwaggerAnnotationScanner]
 * into a complete OpenAPI 3.0.0 JSON string.
 *
 * Has no PSI dependencies; operates entirely on the Kotlin model types from the `model` package.
 * Not used for YAML/JSON spec files — those are converted directly in
 * [com.github.swaggerViewer.view.SwaggerViewerYamlPanel] [step 02-Y].
 *
 * ## Output structure
 * Follows the OAS 3.0 object hierarchy in canonical section order:
 * `openapi` → `info` → `servers` → `tags` → `paths` → `components`
 *
 * [LinkedHashMap] is used throughout so the field order in the output JSON is deterministic
 * and matches the canonical OAS spec section order.
 *
 * ## Key serialization rules
 *  - `@Schema(implementation = Foo.class)` → `$ref: "#/components/schemas/Foo"` (type omitted;
 *    `$ref` and `type` cannot coexist in OAS 3.0)
 *  - `x-` extension fields are merged at the top level of the operation object
 *  - Empty responses default to `{ "200": { "description": "OK" } }`
 *
 * @see com.github.swaggerViewer.view.SwaggerPreviewHtmlBuilder
 */
class SwaggerAnnotationSerializer {

    private val mapper = ObjectMapper()

    // Converts the entire ScanResult to an OpenAPI 3.0.0 JSON string.
    fun build(result: ScanResult): String {
        val spec = LinkedHashMap<String, Any>()
        spec["openapi"] = "3.0.0"

        val info = LinkedHashMap<String, Any>()
        info["title"] = result.title
        info["version"] = result.version
        if (!result.description.isNullOrBlank()) info["description"] = result.description
        if (result.contact != null) {
            val c = result.contact
            info["contact"] = buildMap<String, Any> {
                if (c.name != null) put("name", c.name)
                if (c.email != null) put("email", c.email)
                if (c.url != null) put("url", c.url)
            }
        }
        if (result.license != null) {
            val l = result.license
            info["license"] = buildMap<String, Any> {
                put("name", l.name)
                if (l.url != null) put("url", l.url)
            }
        }
        spec["info"] = info

        if (result.servers.isNotEmpty()) {
            spec["servers"] = result.servers.map { buildServer(it) }
        }

        if (result.tags.isNotEmpty()) {
            spec["tags"] = result.tags.map { tag ->
                buildMap<String, Any> {
                    put("name", tag.name)
                    if (tag.description != null) put("description", tag.description)
                    if (tag.externalDocs != null) put("externalDocs", buildExternalDoc(tag.externalDocs))
                }
            }
        }

        spec["paths"] = result.paths.mapValues { (_, ops) ->
            ops.mapValues { (_, op) -> buildOperation(op) }
        }.ifEmpty { emptyMap<String, Any>() }

        val hasSchemas = result.schemas.isNotEmpty()
        val hasSecuritySchemes = result.securitySchemes.isNotEmpty()
        if (hasSchemas || hasSecuritySchemes) {
            spec["components"] = buildMap<String, Any> {
                if (hasSchemas) put("schemas", result.schemas.mapValues { (_, s) -> buildSchemaDefinition(s) })
                if (hasSecuritySchemes) put("securitySchemes", result.securitySchemes.mapValues { (_, s) -> buildSecurityScheme(s) })
            }
        }

        return mapper.writeValueAsString(spec)
    }

    // @Operation → OpenAPI paths.[path].[method] object
    private fun buildOperation(op: Operation): Map<String, Any> = buildMap {
        if (op.tags.isNotEmpty()) put("tags", op.tags)
        if (op.summary != null) put("summary", op.summary)
        if (op.description != null) put("description", op.description)
        if (op.operationId != null) put("operationId", op.operationId)
        if (op.externalDocs != null) put("externalDocs", buildExternalDoc(op.externalDocs))
        if (op.parameters.isNotEmpty()) put("parameters", op.parameters.map { buildParameter(it) })
        if (op.requestBody != null) put("requestBody", buildRequestBody(op.requestBody))
        put("responses", buildResponses(op.responses))
        if (op.deprecated) put("deprecated", true)
        if (op.security.isNotEmpty()) put("security", op.security.map { buildSecurityRequirement(it) })
        if (op.servers.isNotEmpty()) put("servers", op.servers.map { buildServer(it) })
        if (op.callbacks.isNotEmpty()) put("callbacks", buildCallbacks(op.callbacks))
        // x- extension fields are merged at the top level of the operation object
        op.extensions.forEach { (key, props) -> put(key, props) }
    }

    // @Callback → callbacks.[name] object.
    // OpenAPI shape: { "[expression]": { "[method]": { operation object } } }
    private fun buildCallbacks(callbacks: Map<String, Callback>): Map<String, Any> =
        callbacks.mapValues { (_, cb) ->
            mapOf(cb.expression to mapOf(cb.method to buildOperation(cb.operation)))
        }

    // @ExternalDocumentation → externalDocs object
    private fun buildExternalDoc(doc: ExternalDocumentation): Map<String, Any> = buildMap {
        put("url", doc.url)
        if (!doc.description.isNullOrBlank()) put("description", doc.description)
    }

    // @Parameter → parameters array entry
    private fun buildParameter(p: Parameter): Map<String, Any> = buildMap {
        put("name", p.name)
        put("in", p.location)
        put("required", p.required)
        if (p.description != null) put("description", p.description)
        if (p.example != null) put("example", p.example)
        put("schema", if (p.schema != null) buildSchema(p.schema) else mapOf("type" to "string"))
    }

    // @Schema → inline schema or $ref object.
    // If implementation is present, $ref takes priority — $ref and type cannot coexist.
    private fun buildSchema(schema: Schema): Map<String, Any> {
        if (schema.implementation != null) return mapOf("\$ref" to "#/components/schemas/${schema.implementation}")
        return buildMap {
            if (schema.type != null) put("type", schema.type)
            if (schema.format != null) put("format", schema.format)
            if (schema.minimum != null) put("minimum", schema.minimum)
            if (schema.maximum != null) put("maximum", schema.maximum)
            if (schema.defaultValue != null) put("default", schema.defaultValue)
            if (schema.allowableValues.isNotEmpty()) put("enum", schema.allowableValues)
        }
    }

    // @RequestBody → requestBody object. Uses $ref if contentSchema is set, otherwise a generic object.
    private fun buildRequestBody(rb: RequestBody): Map<String, Any> = buildMap {
        if (rb.description != null) put("description", rb.description)
        put("required", rb.required)
        val schemaMap: Map<String, Any> = if (rb.contentSchema != null)
            mapOf("\$ref" to "#/components/schemas/${rb.contentSchema}")
        else
            mapOf("type" to "object")
        put("content", mapOf("application/json" to mapOf("schema" to schemaMap)))
    }

    // Converts a responseCode → ApiResponse map to an OpenAPI responses object. Defaults to "200 OK" if empty.
    private fun buildResponses(responses: Map<String, ApiResponse>): Map<String, Any> {
        if (responses.isEmpty()) return mapOf("200" to mapOf("description" to "OK"))
        return responses.mapValues { (_, r) -> buildApiResponse(r) }
    }

    // @ApiResponse → responses.[code] object
    private fun buildApiResponse(r: ApiResponse): Map<String, Any> = buildMap {
        put("description", r.description)
        if (r.contentSchema != null) {
            put("content", mapOf(
                "application/json" to mapOf(
                    "schema" to mapOf("\$ref" to "#/components/schemas/${r.contentSchema}")
                )
            ))
        }
        if (r.headers.isNotEmpty()) put("headers", r.headers.mapValues { (_, h) -> buildHeader(h) })
        if (r.links.isNotEmpty()) put("links", r.links.mapValues { (_, l) -> buildLink(l) })
    }

    // @Header → headers.[name] object
    private fun buildHeader(h: Header): Map<String, Any> = buildMap {
        if (h.description != null) put("description", h.description)
        put("schema", if (h.schema != null) buildSchema(h.schema) else mapOf("type" to "string"))
    }

    // @Link → links.[name] object
    private fun buildLink(l: Link): Map<String, Any> = buildMap {
        if (l.description != null) put("description", l.description)
        if (l.operationId != null) put("operationId", l.operationId)
        if (l.operationRef != null) put("operationRef", l.operationRef)
        if (l.server != null) put("server", buildServer(l.server))
        if (l.parameters.isNotEmpty()) put("parameters", l.parameters)
    }

    // @SecurityRequirement → security array entry in { name: [scopes] } form.
    private fun buildSecurityRequirement(s: SecurityRequirement): Map<String, Any> =
        mapOf(s.name to s.scopes)

    // SchemaDefinition → components/schemas.[name] object
    private fun buildSchemaDefinition(schema: SchemaDefinition): Map<String, Any> = buildMap {
        if (schema.enumValues != null) {
            put("type", "string")
            put("enum", schema.enumValues)
            return@buildMap
        }
        put("type", "object")
        if (!schema.description.isNullOrBlank()) put("description", schema.description)
        if (schema.properties.isNotEmpty()) {
            put("properties", schema.properties.mapValues { (_, p) -> buildPropertySchema(p) })
        }
        if (schema.required.isNotEmpty()) put("required", schema.required)
    }

    // PropertySchema → inline schema or $ref object
    private fun buildPropertySchema(prop: PropertySchema): Map<String, Any> = buildMap {
        when {
            prop.ref != null -> {
                put("\$ref", "#/components/schemas/${prop.ref}")
                // description/example cannot be siblings of $ref in OAS 3.0 — skip them here
            }
            prop.type == "array" -> {
                put("type", "array")
                if (prop.items != null) put("items", buildPropertySchema(prop.items))
                if (!prop.description.isNullOrBlank()) put("description", prop.description)
                if (!prop.example.isNullOrBlank()) put("example", prop.example)
            }
            else -> {
                if (prop.type != null) put("type", prop.type)
                if (prop.format != null) put("format", prop.format)
                if (!prop.description.isNullOrBlank()) put("description", prop.description)
                if (!prop.example.isNullOrBlank()) put("example", prop.example)
            }
        }
        if (prop.nullable) put("nullable", true)
    }

    // OasSecurityScheme → components/securitySchemes.[name] object
    private fun buildSecurityScheme(s: OasSecurityScheme): Map<String, Any> = buildMap {
        put("type", s.type)
        if (s.description != null) put("description", s.description)
        if (s.scheme != null) put("scheme", s.scheme)
        if (s.bearerFormat != null) put("bearerFormat", s.bearerFormat)
        if (s.paramName != null) put("name", s.paramName)
        if (s.location != null) put("in", s.location)
    }

    // @Server → servers array entry
    private fun buildServer(s: Server): Map<String, Any> = buildMap {
        put("url", s.url)
        if (!s.description.isNullOrBlank()) put("description", s.description)
        if (s.variables.isNotEmpty()) {
            put("variables", s.variables.mapValues { (_, v) ->
                buildMap<String, Any> {
                    put("default", v.defaultValue)
                    if (v.allowableValues.isNotEmpty()) put("enum", v.allowableValues)
                    if (v.description != null) put("description", v.description)
                }
            })
        }
    }
}
