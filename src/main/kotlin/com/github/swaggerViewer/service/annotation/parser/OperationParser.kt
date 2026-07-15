package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.ApiResponse
import com.github.swaggerViewer.model.swagger.Callback
import com.github.swaggerViewer.model.swagger.ExternalDocumentation
import com.github.swaggerViewer.model.swagger.Header
import com.github.swaggerViewer.model.swagger.Link
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.Parameter
import com.github.swaggerViewer.model.swagger.RequestBody
import com.github.swaggerViewer.model.swagger.Schema
import com.github.swaggerViewer.model.swagger.SecurityRequirement
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiMethod

/**
 * Builds [Operation] objects from a combination of Spring MVC and Swagger OAS annotations.
 * Handles: @Operation, @Parameter(s), @RequestBody, @ApiResponse(s),
 *          @Callback(s), @Extension, @ExternalDocumentation.
 *
 * Spring binding annotations (@PathVariable, @RequestParam, @RequestBody) take priority over
 * matching OAS entries with the same parameter name.
 *
 * @param securityParser used for security requirement parsing (shared with class-level logic)
 * @param globalParser   used for server list extraction (shared format across doc/operation/callback)
 */
class OperationParser(
    private val reader: AnnotationValueReader,
    private val securityParser: SecurityParser,
    private val globalParser: GlobalInfoParser,
) {

    // Combines @Operation annotation and Spring binding info to build an Operation model.
    fun buildOperation(
        method: PsiMethod,
        defaultTags: List<String>,
        classSecurity: List<SecurityRequirement>
    ): Operation {
        val opAnn = method.getAnnotation(Annotation.OPERATION.fqn)
        val summary = reader.getAnnotationStringValue(opAnn, "summary") ?: method.name
        val description = reader.getAnnotationStringValue(opAnn, "description")
        val operationId = reader.getAnnotationStringValue(opAnn, "operationId") ?: method.name
        val deprecated = reader.getAnnotationBooleanValue(opAnn, "deprecated")
        val hidden = method.getAnnotation(Annotation.HIDDEN.fqn) != null ||
                     reader.getAnnotationBooleanValue(opAnn, "hidden")
        val externalDocs = parseExternalDoc(opAnn, "externalDocs")

        // Use @Operation.tags if present; otherwise inherit from the class-level @Tag
        val opTags = if (opAnn != null) reader.getAnnotationStringArray(opAnn, "tags") else emptyList()
        val tags = opTags.ifEmpty { defaultTags }

        val parameters = buildParameters(method, opAnn)
        val requestBody = buildRequestBody(method, opAnn)
        val responses = buildResponses(method, opAnn)

        // Use operation-level security if present; otherwise inherit from the class level
        val opSecurity = securityParser.parseSecurityRequirements(opAnn, "security")
        val security = opSecurity.ifEmpty { classSecurity }

        val servers = if (opAnn != null) globalParser.extractServers(opAnn) else emptyList()
        val callbacks = parseCallbacks(method)
        val extensions = parseExtensions(opAnn)

        return Operation(
            summary = summary,
            description = description,
            tags = tags,
            operationId = operationId,
            deprecated = deprecated,
            hidden = hidden,
            externalDocs = externalDocs,
            parameters = parameters,
            requestBody = requestBody,
            responses = responses,
            security = security,
            servers = servers,
            callbacks = callbacks,
            extensions = extensions
        )
    }

    // ── Parameters ───────────────────────────────────────────────────────────

    // Assembles the parameter list.
    // Spring-bound params (@PathVariable / @RequestParam) take priority: any extra @Parameter /
    // @Parameters / @Operation.parameters entry with the same name is dropped.
    // @Parameter on a method param enriches description/example/schema of the bound param.
    private fun buildParameters(method: PsiMethod, opAnn: PsiAnnotation?): List<Parameter> {
        // Collect extra parameters from @Operation.parameters, @Parameters, and standalone @Parameter
        val extraParams = mutableMapOf<String, Parameter>()
        reader.parseAnnotationArray(opAnn?.findAttributeValue("parameters")).forEach { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@forEach
            extraParams[pi.name] = pi
        }
        method.getAnnotation(Annotation.PARAMETERS.fqn)?.let { parametersAnn ->
            reader.parseAnnotationArray(parametersAnn.findAttributeValue("value")).forEach { ann ->
                val pi = parseParameterAnnotation(ann) ?: return@forEach
                extraParams[pi.name] = pi
            }
        }
        method.getAnnotation(Annotation.PARAMETER.fqn)?.let { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@let
            extraParams[pi.name] = pi
        }

        // Extract params from Spring binding annotations; remove matching extra params by name
        val boundParams = mutableListOf<Parameter>()
        for (param in method.parameterList.parameters) {
            // Enrich description/example/schema with @Parameter on the method parameter
            val paramAnn = param.getAnnotation(Annotation.PARAMETER.fqn)
            when {
                param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null -> continue
                param.getAnnotation(Annotation.PATH_VARIABLE.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.PATH_VARIABLE.fqn)!!
                    val name = reader.getAnnotationStringValue(ann, "value")
                        ?: reader.getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    boundParams.add(Parameter(
                        name = name,
                        location = "path",
                        description = paramAnn?.let { reader.getAnnotationStringValue(it, "description") },
                        required = true,
                        example = paramAnn?.let { reader.getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
                param.getAnnotation(Annotation.REQUEST_PARAM.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.REQUEST_PARAM.fqn)!!
                    val name = reader.getAnnotationStringValue(ann, "value")
                        ?: reader.getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    val required = ann.findAttributeValue("required")?.text != "false"
                    boundParams.add(Parameter(
                        name = name,
                        location = "query",
                        description = paramAnn?.let { reader.getAnnotationStringValue(it, "description") },
                        required = required,
                        example = paramAnn?.let { reader.getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
            }
        }

        return boundParams + extraParams.values
    }

    // Converts a single @Parameter annotation to a Parameter model. Returns null if name is missing.
    private fun parseParameterAnnotation(ann: PsiAnnotation): Parameter? {
        val name = reader.getAnnotationStringValue(ann, "name") ?: return null
        val location = getParameterLocation(ann)
        val desc = reader.getAnnotationStringValue(ann, "description")
        val required = reader.getAnnotationBooleanValue(ann, "required")
        val example = reader.getAnnotationStringValue(ann, "example")
        val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
        return Parameter(name, location, desc, required, example, schema)
    }

    // Extracts the location string from an enum reference such as @Parameter(in = ParameterIn.PATH).
    private fun getParameterLocation(ann: PsiAnnotation): String {
        val text = ann.findAttributeValue("in")?.text ?: return "query"
        return when {
            "PATH" in text -> "path"
            "HEADER" in text -> "header"
            "COOKIE" in text -> "cookie"
            else -> "query"
        }
    }

    // ── Request body ─────────────────────────────────────────────────────────

    // Extracts the request body.
    // @Operation.requestBody (OAS annotation) takes priority if present;
    // otherwise detects the presence of Spring @RequestBody binding.
    private fun buildRequestBody(method: PsiMethod, opAnn: PsiAnnotation?): RequestBody? {
        val oasRbAnn = opAnn?.findAttributeValue("requestBody") as? PsiAnnotation
        if (oasRbAnn != null) {
            val desc = reader.getAnnotationStringValue(oasRbAnn, "description")
            val required = oasRbAnn.findAttributeValue("required")?.text != "false"
            val contentSchema = extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
            return RequestBody(desc, required, contentSchema)
        }

        for (param in method.parameterList.parameters) {
            if (param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null) {
                return RequestBody(null, true, null)
            }
        }
        return null
    }

    // ── Responses ────────────────────────────────────────────────────────────

    // Collects the response list.
    // Priority: @Operation.responses → standalone @ApiResponse → @ApiResponses.
    // For duplicate responseCodes, the first-seen value wins (putIfAbsent).
    private fun buildResponses(method: PsiMethod, opAnn: PsiAnnotation?): Map<String, ApiResponse> {
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
    private fun parseApiResponse(ann: PsiAnnotation): ApiResponse {
        val desc = reader.getAnnotationStringValue(ann, "description") ?: "OK"
        val contentSchema = extractSchemaFromContent(ann.findAttributeValue("content"))
        val headers = parseHeaders(ann.findAttributeValue("headers"))
        val links = parseLinks(ann.findAttributeValue("links"))
        return ApiResponse(desc, contentSchema, headers, links)
    }

    // Converts a @Header array to a name → Header map.
    private fun parseHeaders(attr: PsiAnnotationMemberValue?): Map<String, Header> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = reader.getAnnotationStringValue(ann, "description")
            val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
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

    // ── Callbacks ────────────────────────────────────────────────────────────

    // Collects @Callback / @Callbacks annotations on a method into a name → Callback map.
    // Handles both the single @Callback and the repeatable @Callbacks container.
    private fun parseCallbacks(method: PsiMethod): Map<String, Callback> {
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
        val operation = buildCallbackOperation(opAnn)
        return name to Callback(expression, httpMethod, operation)
    }

    // Builds an Operation from a @Callback's nested @Operation annotation.
    // Unlike buildOperation(), there is no PsiMethod or Spring binding — parameters and requestBody
    // come solely from the OAS annotation attributes.
    private fun buildCallbackOperation(opAnn: PsiAnnotation): Operation {
        val summary = reader.getAnnotationStringValue(opAnn, "summary")
        val description = reader.getAnnotationStringValue(opAnn, "description")
        val operationId = reader.getAnnotationStringValue(opAnn, "operationId")
        val deprecated = reader.getAnnotationBooleanValue(opAnn, "deprecated")
        val externalDocs = parseExternalDoc(opAnn, "externalDocs")
        val tags = reader.getAnnotationStringArray(opAnn, "tags")

        val parameters = reader.parseAnnotationArray(opAnn.findAttributeValue("parameters"))
            .mapNotNull { parseParameterAnnotation(it) }

        val oasRbAnn = opAnn.findAttributeValue("requestBody") as? PsiAnnotation
        val requestBody = if (oasRbAnn != null) {
            val desc = reader.getAnnotationStringValue(oasRbAnn, "description")
            val required = oasRbAnn.findAttributeValue("required")?.text != "false"
            val contentSchema = extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
            RequestBody(desc, required, contentSchema)
        } else null

        val responses = mutableMapOf<String, ApiResponse>()
        reader.parseAnnotationArray(opAnn.findAttributeValue("responses")).forEach { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: return@forEach
            responses[code] = parseApiResponse(ann)
        }

        return Operation(
            summary = summary,
            description = description,
            tags = tags,
            operationId = operationId,
            deprecated = deprecated,
            hidden = false,
            externalDocs = externalDocs,
            parameters = parameters,
            requestBody = requestBody,
            responses = responses.ifEmpty { mapOf("200" to ApiResponse("OK")) },
            security = securityParser.parseSecurityRequirements(opAnn, "security"),
            servers = globalParser.extractServers(opAnn),
            callbacks = emptyMap(),
            extensions = parseExtensions(opAnn)
        )
    }

    // ── Extensions ───────────────────────────────────────────────────────────

    // Parses @Extension / @ExtensionProperty from an annotation's "extensions" attribute.
    // Extension names without the "x-" prefix are normalized to include it.
    private fun parseExtensions(ann: PsiAnnotation?): Map<String, Map<String, String>> {
        if (ann == null) return emptyMap()
        val result = mutableMapOf<String, Map<String, String>>()
        reader.parseAnnotationArray(ann.findAttributeValue("extensions")).forEach { extAnn ->
            val rawName = reader.getAnnotationStringValue(extAnn, "name") ?: return@forEach
            val key = if (rawName.startsWith("x-")) rawName else "x-$rawName"
            val props = reader.parseAnnotationArray(extAnn.findAttributeValue("properties"))
                .mapNotNull { propAnn ->
                    val propName = reader.getAnnotationStringValue(propAnn, "name") ?: return@mapNotNull null
                    val propValue = reader.getAnnotationStringValue(propAnn, "value") ?: return@mapNotNull null
                    propName to propValue
                }.toMap()
            if (props.isNotEmpty()) result[key] = props
        }
        return result
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    // Extracts @ExternalDocumentation from the given attribute of an annotation. Returns null if url is missing.
    private fun parseExternalDoc(ann: PsiAnnotation?, attribute: String): ExternalDocumentation? {
        if (ann == null) return null
        val extDocAnn = ann.findAttributeValue(attribute) as? PsiAnnotation ?: return null
        val url = reader.getAnnotationStringValue(extDocAnn, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = reader.getAnnotationStringValue(extDocAnn, "description")
        return ExternalDocumentation(desc, url)
    }

    // Converts a @Schema annotation to a Schema model. Returns null if no valid attributes are present.
    private fun parseSchemaInfo(schemaAnn: PsiAnnotation?): Schema? {
        if (schemaAnn == null) return null
        val type = reader.getAnnotationStringValue(schemaAnn, "type")
        val format = reader.getAnnotationStringValue(schemaAnn, "format")
        val minimum = reader.getAnnotationStringValue(schemaAnn, "minimum")
        val maximum = reader.getAnnotationStringValue(schemaAnn, "maximum")
        val defaultValue = reader.getAnnotationStringValue(schemaAnn, "defaultValue")
        val allowableValues = reader.getAnnotationStringArray(schemaAnn, "allowableValues")
        val implementation = getImplementationClassName(schemaAnn)
        if (type == null && format == null && minimum == null && maximum == null &&
            defaultValue == null && implementation == null && allowableValues.isEmpty()) return null
        return Schema(type, format, minimum, maximum, defaultValue, allowableValues, implementation)
    }

    // Extracts the simple class name from @Schema(implementation = Foo.class).
    // Void.class / void.class are treated as "no schema" and return null.
    private fun getImplementationClassName(schemaAnn: PsiAnnotation): String? {
        val impl = schemaAnn.findAttributeValue("implementation") ?: return null
        return when (impl) {
            is PsiClassObjectAccessExpression ->
                impl.operand.type.canonicalText.substringAfterLast('.').takeIf { name ->
                    name.isNotEmpty() && name != "Void" && name != "void"
                }
            else -> null
        }
    }

    // Extracts the simple class name from @Content(schema = @Schema(implementation = Foo.class)).
    // Only the first Content entry is used when the attribute is an array.
    private fun extractSchemaFromContent(contentValue: PsiAnnotationMemberValue?): String? {
        val contentAnn = reader.parseAnnotationArray(contentValue).firstOrNull() ?: return null
        val schemaAnn = contentAnn.findAttributeValue("schema") as? PsiAnnotation ?: return null
        return getImplementationClassName(schemaAnn)
    }
}
