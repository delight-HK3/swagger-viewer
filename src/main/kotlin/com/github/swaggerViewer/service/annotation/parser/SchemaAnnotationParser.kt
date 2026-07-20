package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.swagger.Schema
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClassObjectAccessExpression

/**
 * [step 05-A-4] Converts inline `@Schema` annotation attributes to [Schema] model objects.
 *
 * Reads scalar attributes (`type`, `format`, `minimum`, `maximum`, `defaultValue`,
 * `allowableValues`) and the `implementation` class reference from a `@Schema` annotation
 * placed on a parameter, request body content, or response content.
 *
 * Distinct from [com.github.swaggerViewer.service.annotation.PsiSchemaAnalyzer] [step 06-A],
 * which performs BFS class-graph traversal to build full `components/schemas` definitions.
 * This class only reads inline annotation attributes — it does not traverse class fields.
 *
 * Used by [ParameterParser] [step 05-A-5], [ResponseParser] [step 05-A-6],
 * and [CallbackParser] [step 05-A-8].
 */
class SchemaAnnotationParser(private val reader: AnnotationValueReader) {

    // Converts a @Schema annotation to a Schema model. Returns null if no valid attributes are present.
    fun parseSchemaInfo(schemaAnn: PsiAnnotation?): Schema? {
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
    fun getImplementationClassName(schemaAnn: PsiAnnotation): String? {
        val impl = schemaAnn.findAttributeValue("implementation") ?: return null
        return when (impl) {
            is PsiClassObjectAccessExpression ->
                impl.operand.type.canonicalText.substringAfterLast('.').takeIf { name ->
                    name.isNotEmpty() && name != "Void" && name != "void"
                }
            else -> null
        }
    }

    // Extracts the implementation class name from @Content(schema = @Schema(implementation = Foo.class)).
    // Only the first Content entry is used when the attribute is an array.
    fun extractSchemaFromContent(contentValue: PsiAnnotationMemberValue?): String? {
        val contentAnn = reader.parseAnnotationArray(contentValue).firstOrNull() ?: return null
        val schemaAnn = contentAnn.findAttributeValue("schema") as? PsiAnnotation ?: return null
        return getImplementationClassName(schemaAnn)
    }
}
