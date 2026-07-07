package com.example.swaggerViewer.service

import com.example.swaggerViewer.model.ExternalDocInfo
import com.example.swaggerViewer.model.HeaderInfo
import com.example.swaggerViewer.model.LinkInfo
import com.example.swaggerViewer.model.OperationInfo
import com.example.swaggerViewer.model.ParameterInfo
import com.example.swaggerViewer.model.RequestBodyInfo
import com.example.swaggerViewer.model.ResponseInfo
import com.example.swaggerViewer.model.ScanResult
import com.example.swaggerViewer.model.SchemaInfo
import com.example.swaggerViewer.model.SecurityRequirementInfo
import com.example.swaggerViewer.model.ServerInfo
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * PsiSwaggerScanner.ScanResult를 OpenAPI 3.0.0 JSON 문자열로 변환한다.
 * 필드 순서를 deterministic하게 유지하기 위해 LinkedHashMap을 사용한다.
 */
class OpenApiSpecBuilder {

    private val mapper = ObjectMapper()

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

    private fun buildOperation(op: OperationInfo): Map<String, Any> = buildMap {
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

    private fun buildExternalDoc(doc: ExternalDocInfo): Map<String, Any> = buildMap {
        put("url", doc.url)
        if (!doc.description.isNullOrBlank()) put("description", doc.description)
    }

    private fun buildParameter(p: ParameterInfo): Map<String, Any> = buildMap {
        put("name", p.name)
        put("in", p.location)
        put("required", p.required)
        if (p.description != null) put("description", p.description)
        if (p.example != null) put("example", p.example)
        put("schema", if (p.schema != null) buildSchema(p.schema) else mapOf("type" to "string"))
    }

    private fun buildSchema(schema: SchemaInfo): Map<String, Any> {
        // implementation은 $ref로 변환. $ref와 type을 동시에 쓸 수 없으므로 우선 처리.
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

    private fun buildRequestBody(rb: RequestBodyInfo): Map<String, Any> = buildMap {
        if (rb.description != null) put("description", rb.description)
        put("required", rb.required)
        val schemaMap: Map<String, Any> = if (rb.contentSchema != null)
            mapOf("\$ref" to "#/components/schemas/${rb.contentSchema}")
        else
            mapOf("type" to "object")
        put("content", mapOf("application/json" to mapOf("schema" to schemaMap)))
    }

    private fun buildResponses(responses: Map<String, ResponseInfo>): Map<String, Any> {
        if (responses.isEmpty()) return mapOf("200" to mapOf("description" to "OK"))
        return responses.mapValues { (_, r) -> buildResponse(r) }
    }

    private fun buildResponse(r: ResponseInfo): Map<String, Any> = buildMap {
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

    private fun buildHeader(h: HeaderInfo): Map<String, Any> = buildMap {
        if (h.description != null) put("description", h.description)
        put("schema", if (h.schema != null) buildSchema(h.schema) else mapOf("type" to "string"))
    }

    private fun buildLink(l: LinkInfo): Map<String, Any> = buildMap {
        if (l.description != null) put("description", l.description)
        if (l.operationId != null) put("operationId", l.operationId)
        if (l.parameters.isNotEmpty()) put("parameters", l.parameters)
    }

    private fun buildSecurityRequirement(s: SecurityRequirementInfo): Map<String, Any> =
        mapOf(s.name to s.scopes)

    private fun buildServer(s: ServerInfo): Map<String, Any> = buildMap {
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
