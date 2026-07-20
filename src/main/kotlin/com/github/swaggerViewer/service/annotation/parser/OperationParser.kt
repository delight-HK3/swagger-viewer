package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.RequestBody
import com.github.swaggerViewer.model.swagger.SecurityRequirement
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiMethod

/**
 * [step 05-A-9] Coordinator — assembles a complete [Operation] model by delegating each
 * concern to a dedicated parser and merging the results.
 *
 * Delegation map:
 *  - Parameters        → [ParameterParser] [step 05-A-5]
 *  - Responses         → [ResponseParser] [step 05-A-6]
 *  - Callbacks         → [CallbackParser] [step 05-A-8]
 *  - Extensions        → [ExtensionParser] [step 05-A-7]
 *  - Security          → [SecurityParser] [step 05-A-3]
 *  - Servers / extDocs → [GlobalInfoParser] [step 05-A-2]
 *  - Schema (inline)   → [SchemaAnnotationParser] [step 05-A-4] (request body content)
 *
 * Inheritance rules applied here (not in sub-parsers):
 *  - Tags: `@Operation.tags` if present, otherwise inherited from class-level `@Tag`
 *  - Security: operation-level if present, otherwise inherited from class-level `@SecurityRequirement`
 *
 * Spring binding annotations (`@PathVariable`, `@RequestParam`, `@RequestBody`) take priority
 * over matching OAS entries with the same parameter name.
 */
class OperationParser(
    private val reader: AnnotationValueReader,
    private val securityParser: SecurityParser,
    private val globalParser: GlobalInfoParser,
    private val schemaParser: SchemaAnnotationParser,
    private val parameterParser: ParameterParser,
    private val responseParser: ResponseParser,
    private val callbackParser: CallbackParser,
    private val extensionParser: ExtensionParser,
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
        val externalDocs = globalParser.parseExternalDoc(opAnn, "externalDocs")

        // Use @Operation.tags if present; otherwise inherit from the class-level @Tag
        val opTags = if (opAnn != null) reader.getAnnotationStringArray(opAnn, "tags") else emptyList()
        val tags = opTags.ifEmpty { defaultTags }

        // Use operation-level security if present; otherwise inherit from the class level
        val opSecurity = securityParser.parseSecurityRequirements(opAnn, "security")
        val security = opSecurity.ifEmpty { classSecurity }

        return Operation(
            summary = summary,
            description = description,
            tags = tags,
            operationId = operationId,
            deprecated = deprecated,
            hidden = hidden,
            externalDocs = externalDocs,
            parameters = parameterParser.buildParameters(method, opAnn),
            requestBody = buildRequestBody(method, opAnn),
            responses = responseParser.buildResponses(method, opAnn),
            security = security,
            servers = if (opAnn != null) globalParser.extractServers(opAnn) else emptyList(),
            callbacks = callbackParser.parseCallbacks(method),
            extensions = extensionParser.parseExtensions(opAnn)
        )
    }

    // Extracts the request body from @Operation.requestBody (OAS annotation) only.
    // Spring @RequestBody is intentionally ignored — it carries no OAS documentation info.
    private fun buildRequestBody(method: PsiMethod, opAnn: PsiAnnotation?): RequestBody? {
        val oasRbAnn = opAnn?.findAttributeValue("requestBody") as? PsiAnnotation ?: return null
        return RequestBody(
            description = reader.getAnnotationStringValue(oasRbAnn, "description"),
            required = oasRbAnn.findAttributeValue("required")?.text != "false",
            contentSchema = schemaParser.extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
        )
    }
}
