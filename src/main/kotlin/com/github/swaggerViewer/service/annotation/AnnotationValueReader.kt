package com.github.swaggerViewer.service.annotation

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression

/**
 * Low-level utility for reading PSI annotation attribute values as Kotlin primitive types.
 * Has no model type dependencies.
 */
class AnnotationValueReader {

    // Normalizes a PsiAnnotationMemberValue to a list of PsiAnnotation.
    fun parseAnnotationArray(attr: PsiAnnotationMemberValue?): List<PsiAnnotation> {
        if (attr == null) return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(attr)
            else -> emptyList()
        }
    }

    // Reads a string array from an annotation attribute. Returns a list even for a single value.
    fun getAnnotationStringArray(ann: PsiAnnotation, attribute: String): List<String> {
        val attr = ann.findAttributeValue(attribute) ?: return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.mapNotNull { extractStringValue(it) }
            else -> listOfNotNull(extractStringValue(attr))
        }
    }

    // Reads a boolean value from an annotation attribute. Returns false if the annotation or attribute is absent.
    fun getAnnotationBooleanValue(annotation: PsiAnnotation?, attribute: String): Boolean {
        if (annotation == null) return false
        val value = annotation.findAttributeValue(attribute) ?: return false
        return (value as? PsiLiteralExpression)?.value as? Boolean ?: (value.text == "true")
    }

    // Reads a single string value from an annotation attribute. Returns null if absent.
    fun getAnnotationStringValue(annotation: PsiAnnotation?, attribute: String): String? {
        if (annotation == null) return null
        return extractStringValue(annotation.findAttributeValue(attribute) ?: return null)
    }

    // Extracts the actual string from a PSI annotation value.
    // Handles in order: literal → array (first element) → static field reference → text fallback.
    private fun extractStringValue(value: PsiAnnotationMemberValue): String? = when (value) {
        is PsiLiteralExpression -> value.value as? String
        is PsiArrayInitializerMemberValue ->
            value.initializers.firstOrNull()?.let { extractStringValue(it) }
        is PsiReferenceExpression -> {
            val resolved = value.resolve()
            if (resolved is PsiField && resolved.hasModifierProperty(PsiModifier.STATIC)) {
                (resolved.initializer as? PsiLiteralExpression)?.value as? String
            } else null
        }
        else -> value.text?.trim('"')?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
    }
}
