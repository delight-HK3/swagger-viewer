package com.example.swaggerViewer.service

import com.example.swaggerViewer.model.ScanResult
import com.example.swaggerViewer.model.swagger.ApiResponse
import com.example.swaggerViewer.model.swagger.ExternalDocumentation
import com.example.swaggerViewer.model.swagger.Header
import com.example.swaggerViewer.model.swagger.Link
import com.example.swaggerViewer.model.swagger.Operation
import com.example.swaggerViewer.model.swagger.Parameter
import com.example.swaggerViewer.model.swagger.RequestBody
import com.example.swaggerViewer.model.swagger.Schema
import com.example.swaggerViewer.model.swagger.SecurityRequirement
import com.example.swaggerViewer.model.swagger.Server;
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * [ScanResult]를 OpenAPI 3.0.0 JSON 문자열로 직렬화한다.
 *
 * 각 build* 메서드는 OpenAPI 스펙 객체 하나를 Map으로 변환하며,
 * 최종적으로 Jackson ObjectMapper가 JSON 문자열로 출력한다.
 * 필드 삽입 순서 = 스펙 출력 순서이므로 LinkedHashMap을 일관되게 사용한다.
 */
class OpenApiSpecBuilder {

    private val mapper = ObjectMapper()

    /** ScanResult 전체를 OpenAPI 3.0.0 JSON 문자열로 변환한다. */
    fun build(result: ScanResult): String {
        val spec = LinkedHashMap<String, Any>()
        spec["openapi"] = "3.0.0"

        val info = LinkedHashMap<String, Any>()
        info["title"] = result.title
        info["version"] = result.version
        if (!result.description.isNullOrBlank()) info["description"] = result.description
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

        return mapper.writeValueAsString(spec)
    }

    /** @Operation → OpenAPI paths.[path].[method] 객체 */
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
    }

    /** @ExternalDocumentation → externalDocs 객체 */
    private fun buildExternalDoc(doc: ExternalDocumentation): Map<String, Any> = buildMap {
        put("url", doc.url)
        if (!doc.description.isNullOrBlank()) put("description", doc.description)
    }

    /** @Parameter → parameters 배열 항목 */
    private fun buildParameter(p: Parameter): Map<String, Any> = buildMap {
        put("name", p.name)
        put("in", p.location)
        put("required", p.required)
        if (p.description != null) put("description", p.description)
        if (p.example != null) put("example", p.example)
        put("schema", if (p.schema != null) buildSchema(p.schema) else mapOf("type" to "string"))
    }

    /**
     * @Schema → 인라인 스키마 또는 $ref 객체.
     * implementation이 있으면 $ref 우선 — $ref와 type을 동시에 쓸 수 없다.
     */
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

    /** @RequestBody → requestBody 객체. contentSchema가 있으면 $ref, 없으면 generic object. */
    private fun buildRequestBody(rb: RequestBody): Map<String, Any> = buildMap {
        if (rb.description != null) put("description", rb.description)
        put("required", rb.required)
        val schemaMap: Map<String, Any> = if (rb.contentSchema != null)
            mapOf("\$ref" to "#/components/schemas/${rb.contentSchema}")
        else
            mapOf("type" to "object")
        put("content", mapOf("application/json" to mapOf("schema" to schemaMap)))
    }

    /** responseCode → ApiResponse 맵을 OpenAPI responses 객체로 변환. 비어 있으면 "200 OK" 기본값. */
    private fun buildResponses(responses: Map<String, ApiResponse>): Map<String, Any> {
        if (responses.isEmpty()) return mapOf("200" to mapOf("description" to "OK"))
        return responses.mapValues { (_, r) -> buildApiResponse(r) }
    }

    /** @ApiResponse → responses.[code] 객체 */
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

    /** @Header → headers.[name] 객체 */
    private fun buildHeader(h: Header): Map<String, Any> = buildMap {
        if (h.description != null) put("description", h.description)
        put("schema", if (h.schema != null) buildSchema(h.schema) else mapOf("type" to "string"))
    }

    /** @Link → links.[name] 객체 */
    private fun buildLink(l: Link): Map<String, Any> = buildMap {
        if (l.description != null) put("description", l.description)
        if (l.operationId != null) put("operationId", l.operationId)
        if (l.parameters.isNotEmpty()) put("parameters", l.parameters)
    }

    /** @SecurityRequirement → security 배열 항목. { name: [scopes] } 형태. */
    private fun buildSecurityRequirement(s: SecurityRequirement): Map<String, Any> =
        mapOf(s.name to s.scopes)

    /** @Server → servers 배열 항목 */
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
