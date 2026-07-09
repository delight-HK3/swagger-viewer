package com.example.swaggerViewer.service.annotation

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression

/**
 * PSI 어노테이션 값을 Kotlin 기본 타입으로 읽어주는 저수준 유틸.
 * 모델 타입에 대한 의존성이 없어 플러그인 전역에서 재사용 가능하다.
 */
class PsiAnnotationReader {

    /** PsiAnnotationMemberValue를 PsiAnnotation 목록으로 정규화한다. */
    fun parseAnnotationArray(attr: PsiAnnotationMemberValue?): List<PsiAnnotation> {
        if (attr == null) return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(attr)
            else -> emptyList()
        }
    }

    /** 어노테이션 속성에서 문자열 배열을 읽는다. 단일 값이어도 리스트로 반환한다. */
    fun getAnnotationStringArray(ann: PsiAnnotation, attribute: String): List<String> {
        val attr = ann.findAttributeValue(attribute) ?: return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.mapNotNull { extractStringValue(it) }
            else -> listOfNotNull(extractStringValue(attr))
        }
    }

    /** 어노테이션 속성에서 boolean 값을 읽는다. 어노테이션이 없거나 속성이 없으면 false. */
    fun getAnnotationBooleanValue(annotation: PsiAnnotation?, attribute: String): Boolean {
        if (annotation == null) return false
        val value = annotation.findAttributeValue(attribute) ?: return false
        return (value as? PsiLiteralExpression)?.value as? Boolean ?: (value.text == "true")
    }

    /** 어노테이션 속성에서 문자열 값 하나를 읽는다. 없으면 null. */
    fun getAnnotationStringValue(annotation: PsiAnnotation?, attribute: String): String? {
        if (annotation == null) return null
        return extractStringValue(annotation.findAttributeValue(attribute) ?: return null)
    }

    /**
     * PSI 어노테이션 값에서 실제 문자열을 추출한다.
     * 리터럴·배열(첫 번째 요소)·정적 필드 참조·텍스트 폴백 순으로 처리한다.
     */
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
