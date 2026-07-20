package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.swagger.OasSecurityScheme
import com.github.swaggerViewer.model.swagger.SecurityRequirement
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass

/**
 * [step 05-A-3] Parses security-related Swagger annotations:
 *  - `@SecurityScheme` / `@SecuritySchemes` → [OasSecurityScheme] entries for `components/securitySchemes`
 *  - `@SecurityRequirement` / `@SecurityRequirements` → [SecurityRequirement] lists for operations and classes
 *
 * Handles both the single-annotation form and the repeatable container form for each type.
 * Class-level security requirements extracted here serve as the inherited default when a method
 * defines no security of its own — the merge is applied by [OperationParser] [step 05-A-9].
 */
class SecurityParser(private val reader: AnnotationValueReader) {

    // Parses a single @SecurityScheme annotation to a (name → OasSecurityScheme) pair.
    // Returns null if the name attribute is blank.
    fun parseSecuritySchemeAnnotation(ann: PsiAnnotation): Pair<String, OasSecurityScheme>? {
        val name = reader.getAnnotationStringValue(ann, "name")?.takeIf { it.isNotBlank() } ?: return null
        val type = parseSecuritySchemeType(ann)
        val desc = reader.getAnnotationStringValue(ann, "description")?.takeIf { it.isNotBlank() }
        val scheme = reader.getAnnotationStringValue(ann, "scheme")?.takeIf { it.isNotBlank() }
        val bearerFormat = reader.getAnnotationStringValue(ann, "bearerFormat")?.takeIf { it.isNotBlank() }
        val paramName = reader.getAnnotationStringValue(ann, "paramName")?.takeIf { it.isNotBlank() }
        val location = parseSecuritySchemeLocation(ann)
        return name to OasSecurityScheme(type, desc, scheme, bearerFormat, paramName, location)
    }

    // Parses the @SecuritySchemes container and returns all contained schemes as a map.
    fun extractSecuritySchemesFromContainer(ann: PsiAnnotation): Map<String, OasSecurityScheme> =
        reader.parseAnnotationArray(ann.findAttributeValue("value"))
            .mapNotNull { parseSecuritySchemeAnnotation(it) }
            .toMap()

    // Extracts @SecurityRequirement / @SecurityRequirements from a class.
    // Tries @SecurityRequirements (plural container) first; falls back to single @SecurityRequirement.
    // Used as the inherited default when a method has no security defined.
    fun extractClassSecurityRequirements(cls: PsiClass): List<SecurityRequirement> {
        val multiAnn = cls.getAnnotation(Annotation.SECURITY_REQUIREMENTS.fqn)
        if (multiAnn != null) return parseSecurityRequirements(multiAnn, "value")

        val secAnn = cls.getAnnotation(Annotation.SECURITY_REQUIREMENT.fqn) ?: return emptyList()
        val name = reader.getAnnotationStringValue(secAnn, "name") ?: return emptyList()
        val scopes = reader.getAnnotationStringArray(secAnn, "scopes")
        return listOf(SecurityRequirement(name, scopes))
    }

    // Extracts a @SecurityRequirement array from the given attribute of an annotation.
    fun parseSecurityRequirements(ann: PsiAnnotation?, attribute: String): List<SecurityRequirement> =
        reader.parseAnnotationArray(ann?.findAttributeValue(attribute)).mapNotNull { secAnn ->
            val name = reader.getAnnotationStringValue(secAnn, "name") ?: return@mapNotNull null
            val scopes = reader.getAnnotationStringArray(secAnn, "scopes")
            SecurityRequirement(name, scopes)
        }

    // Maps SecuritySchemeType enum to OAS type string. Defaults to "http".
    private fun parseSecuritySchemeType(ann: PsiAnnotation): String {
        val text = ann.findAttributeValue("type")?.text ?: return "http"
        return when {
            "APIKEY" in text -> "apiKey"
            "OAUTH2" in text -> "oauth2"
            "OPENIDCONNECT" in text -> "openIdConnect"
            else -> "http"
        }
    }

    // Maps SecuritySchemeIn enum to OAS location string. Returns null if not set or unrecognized.
    private fun parseSecuritySchemeLocation(ann: PsiAnnotation): String? {
        val text = ann.findAttributeValue("in")?.text ?: return null
        return when {
            "HEADER" in text -> "header"
            "QUERY" in text -> "query"
            "COOKIE" in text -> "cookie"
            else -> null
        }
    }
}
