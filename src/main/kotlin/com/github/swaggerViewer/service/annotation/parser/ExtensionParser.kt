package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation

/**
 * [step 05-A-7] Parses `@Extension` / `@ExtensionProperty` annotations into the OpenAPI
 * extension map format (`x-*` fields).
 *
 * Extension names are normalized to include the `x-` prefix required by the OAS spec
 * if not already present. Shared by [OperationParser] [step 05-A-9] and
 * [CallbackParser] [step 05-A-8].
 */
class ExtensionParser(private val reader: AnnotationValueReader) {

    // Parses @Extension / @ExtensionProperty from an annotation's "extensions" attribute.
    // Extension names without the "x-" prefix are normalized to include it.
    fun parseExtensions(ann: PsiAnnotation?): Map<String, Map<String, String>> {
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
}
