package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.ApiResponse
import com.github.swaggerViewer.model.swagger.Header
import com.github.swaggerViewer.model.swagger.Link
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiMethod

/**
 * [step 05-A-6] Builds the response map for an [Operation] from `@ApiResponse` / `@ApiResponses`.
 *
 * Priority when the same `responseCode` appears in multiple sources:
 *  1. `@Operation(responses = [...])` — highest priority, processed first
 *  2. Standalone `@ApiResponse` on the method
 *  3. `@ApiResponses(value = [...])` on the method — lowest priority (first-seen wins via `putIfAbsent`)
 *
 * Also handles `@Header` and `@Link` arrays nested inside each `@ApiResponse`.
 * [parseApiResponse] is `internal` so [CallbackParser] [step 05-A-8] can reuse it
 * for callback operation responses.
 */
class ResponseParser(
    private val reader: AnnotationValueReader,
    private val globalParser: GlobalInfoParser,
    private val schemaParser: SchemaAnnotationParser,
) {

    // Collects the response list for a method.
    fun buildResponses(method: PsiMethod, opAnn: PsiAnnotation?): Map<String, ApiResponse> {
        val result = mutableMapOf<String, ApiResponse>()

        reader.parseAnnotationArray(opAnn?.findAttributeValue("responses")).forEach { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: return@forEach
            result[code] = parseApiResponse(ann)
        }

        method.getAnnotation(Annotation.API_RESPONSE.fqn)?.let { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: "200"
            result.putIfAbsent(code, parseApiResponse(ann))
        }

        method.getAnnotation(Annotation.API_RESPONSES.fqn)?.let { ann ->
            reader.parseAnnotationArray(ann.findAttributeValue("value")).forEach { responseAnn ->
                val code = reader.getAnnotationStringValue(responseAnn, "responseCode") ?: return@forEach
                result.putIfAbsent(code, parseApiResponse(responseAnn))
            }
        }

        return result.ifEmpty { mapOf("200" to ApiResponse("OK")) }
    }

    // Converts a single @ApiResponse annotation to an ApiResponse model.
    // internal: also used by CallbackParser for callback operation responses
    internal fun parseApiResponse(ann: PsiAnnotation): ApiResponse {
        val desc = reader.getAnnotationStringValue(ann, "description") ?: "OK"
        val contentSchema = schemaParser.extractSchemaFromContent(ann.findAttributeValue("content"))
        val headers = parseHeaders(ann.findAttributeValue("headers"))
        val links = parseLinks(ann.findAttributeValue("links"))
        return ApiResponse(desc, contentSchema, headers, links)
    }

    // Converts a @Header array to a name → Header map.
    private fun parseHeaders(attr: PsiAnnotationMemberValue?): Map<String, Header> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = reader.getAnnotationStringValue(ann, "description")
            val schema = schemaParser.parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
            name to Header(desc, schema)
        }.toMap()

    // Converts a @Link array to a name → Link map.
    private fun parseLinks(attr: PsiAnnotationMemberValue?): Map<String, Link> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = reader.getAnnotationStringValue(ann, "description")
            val opId = reader.getAnnotationStringValue(ann, "operationId")
            val opRef = reader.getAnnotationStringValue(ann, "operationRef")
            val server = (ann.findAttributeValue("server") as? PsiAnnotation)
                ?.let { globalParser.parseServer(it) }
            val params = reader.parseAnnotationArray(ann.findAttributeValue("parameters")).mapNotNull { paramAnn ->
                val pName = reader.getAnnotationStringValue(paramAnn, "name") ?: return@mapNotNull null
                val expr = reader.getAnnotationStringValue(paramAnn, "expression") ?: return@mapNotNull null
                pName to expr
            }.toMap()
            name to Link(desc, opId, opRef, server, params)
        }.toMap()
}
