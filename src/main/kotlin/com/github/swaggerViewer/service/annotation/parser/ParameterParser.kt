package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.Parameter
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiMethod

/**
 * [step 05-A-5] Assembles the parameter list for an [Operation] from two sources:
 *  - **OAS annotations**: `@Parameter`, `@Parameters`, `@Operation(parameters = [...])`
 *  - **Spring binding annotations**: `@PathVariable` (location: `path`) and `@RequestParam` (location: `query`)
 *
 * Merge rule: Spring-bound parameters take priority. If a Spring-bound parameter has the same
 * name as an OAS `@Parameter` entry, the OAS entry is dropped. A `@Parameter` placed directly
 * on a method parameter enriches the bound param's `description`, `example`, and `schema`.
 *
 * [parseParameterAnnotation] is `internal` so [CallbackParser] [step 05-A-8] can reuse it
 * for callback operation parameters, which have no Spring binding context.
 */
class ParameterParser(
    private val reader: AnnotationValueReader,
    private val schemaParser: SchemaAnnotationParser,
) {

    fun buildParameters(method: PsiMethod, opAnn: PsiAnnotation?): List<Parameter> {
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
                        schema = paramAnn?.let { schemaParser.parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
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
                        schema = paramAnn?.let { schemaParser.parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
            }
        }

        return boundParams + extraParams.values
    }

    // Converts a single @Parameter annotation to a Parameter model. Returns null if name is missing.
    // internal: also used by CallbackParser for callback operation parameters
    internal fun parseParameterAnnotation(ann: PsiAnnotation): Parameter? {
        val name = reader.getAnnotationStringValue(ann, "name") ?: return null
        val location = getParameterLocation(ann)
        val desc = reader.getAnnotationStringValue(ann, "description")
        val required = reader.getAnnotationBooleanValue(ann, "required")
        val example = reader.getAnnotationStringValue(ann, "example")
        val schema = schemaParser.parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
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
}
