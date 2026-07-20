package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.ApiResponse
import com.github.swaggerViewer.model.swagger.Callback
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.RequestBody
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiMethod

/**
 * [step 05-A-8] Parses `@Callback` / `@Callbacks` annotations into a name → [Callback] map.
 *
 * Handles both the single `@Callback` form and the repeatable `@Callbacks` container.
 * For each callback, assembles a nested [Operation] via `buildCallbackOperation`, which mirrors
 * [OperationParser.buildOperation] [step 05-A-9] but without any Spring binding context:
 * parameters and request body come solely from OAS annotation attributes.
 *
 * Reuses [ParameterParser.parseParameterAnnotation] [step 05-A-5] and
 * [ResponseParser.parseApiResponse] [step 05-A-6] to avoid duplicating parsing logic.
 */
class CallbackParser(
    private val reader: AnnotationValueReader,
    private val schemaParser: SchemaAnnotationParser,
    private val parameterParser: ParameterParser,
    private val responseParser: ResponseParser,
    private val extensionParser: ExtensionParser,
    private val securityParser: SecurityParser,
    private val globalParser: GlobalInfoParser,
) {

    fun parseCallbacks(method: PsiMethod): Map<String, Callback> {
        val result = mutableMapOf<String, Callback>()

        method.getAnnotation(Annotation.CALLBACKS.fqn)?.let { containersAnn ->
            reader.parseAnnotationArray(containersAnn.findAttributeValue("value")).forEach { ann ->
                val (name, cb) = parseCallbackAnnotation(ann) ?: return@forEach
                result[name] = cb
            }
        }

        // Single @Callback (also covers the case with no @Callbacks wrapper)
        method.getAnnotation(Annotation.CALLBACK.fqn)?.let { ann ->
            val (name, cb) = parseCallbackAnnotation(ann) ?: return@let
            result.putIfAbsent(name, cb)
        }

        return result
    }

    // Converts a single @Callback annotation to a (name, Callback) pair. Returns null if required fields are missing.
    private fun parseCallbackAnnotation(ann: PsiAnnotation): Pair<String, Callback>? {
        val name = reader.getAnnotationStringValue(ann, "name") ?: return null
        val expression = reader.getAnnotationStringValue(ann, "callbackUrlExpression") ?: return null
        val opAnn = ann.findAttributeValue("operation") as? PsiAnnotation ?: return null
        val httpMethod = reader.getAnnotationStringValue(opAnn, "method")
            ?.lowercase()?.takeIf { it.isNotBlank() } ?: "post"
        return name to Callback(expression, httpMethod, buildCallbackOperation(opAnn))
    }

    // Builds an Operation from a @Callback's nested @Operation annotation.
    // No PsiMethod or Spring binding — parameters and requestBody come from OAS annotation attributes only.
    private fun buildCallbackOperation(opAnn: PsiAnnotation): Operation {
        val oasRbAnn = opAnn.findAttributeValue("requestBody") as? PsiAnnotation
        val requestBody = if (oasRbAnn != null) {
            RequestBody(
                description = reader.getAnnotationStringValue(oasRbAnn, "description"),
                required = oasRbAnn.findAttributeValue("required")?.text != "false",
                contentSchema = schemaParser.extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
            )
        } else null

        val responses = mutableMapOf<String, ApiResponse>()
        reader.parseAnnotationArray(opAnn.findAttributeValue("responses")).forEach { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: return@forEach
            responses[code] = responseParser.parseApiResponse(ann)
        }

        return Operation(
            summary = reader.getAnnotationStringValue(opAnn, "summary"),
            description = reader.getAnnotationStringValue(opAnn, "description"),
            tags = reader.getAnnotationStringArray(opAnn, "tags"),
            operationId = reader.getAnnotationStringValue(opAnn, "operationId"),
            deprecated = reader.getAnnotationBooleanValue(opAnn, "deprecated"),
            hidden = false,
            externalDocs = globalParser.parseExternalDoc(opAnn, "externalDocs"),
            parameters = reader.parseAnnotationArray(opAnn.findAttributeValue("parameters"))
                .mapNotNull { parameterParser.parseParameterAnnotation(it) },
            requestBody = requestBody,
            responses = responses.ifEmpty { mapOf("200" to ApiResponse("OK")) },
            security = securityParser.parseSecurityRequirements(opAnn, "security"),
            servers = globalParser.extractServers(opAnn),
            callbacks = emptyMap(),
            extensions = extensionParser.parseExtensions(opAnn)
        )
    }
}
