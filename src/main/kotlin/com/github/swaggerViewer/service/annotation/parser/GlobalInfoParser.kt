package com.github.swaggerViewer.service.annotation.parser

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.HttpMapping
import com.github.swaggerViewer.model.swagger.Contact
import com.github.swaggerViewer.model.swagger.ExternalDocumentation
import com.github.swaggerViewer.model.swagger.License
import com.github.swaggerViewer.model.swagger.Server
import com.github.swaggerViewer.model.swagger.ServerVariable
import com.github.swaggerViewer.model.swagger.Tag
import com.github.swaggerViewer.service.annotation.AnnotationValueReader
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/**
 * Parses document-level and class-level Swagger/Spring annotations:
 *   - @Info (contact, license) from @OpenAPIDefinition
 *   - @Server / @ServerVariable
 *   - @Tag on a controller class
 *   - Spring MVC routing info (@RequestMapping, @GetMapping, etc.)
 */
class GlobalInfoParser(private val reader: AnnotationValueReader) {

    // Extracts @Contact from @Info. Returns null if all fields are blank.
    fun parseContactFromInfo(infoAnn: PsiAnnotation): Contact? {
        val contactAnn = infoAnn.findAttributeValue("contact") as? PsiAnnotation ?: return null
        val name = reader.getAnnotationStringValue(contactAnn, "name")?.takeIf { it.isNotBlank() }
        val email = reader.getAnnotationStringValue(contactAnn, "email")?.takeIf { it.isNotBlank() }
        val url = reader.getAnnotationStringValue(contactAnn, "url")?.takeIf { it.isNotBlank() }
        if (name == null && email == null && url == null) return null
        return Contact(name, email, url)
    }

    // Extracts @License from @Info. Returns null if name is blank.
    fun parseLicenseFromInfo(infoAnn: PsiAnnotation): License? {
        val licenseAnn = infoAnn.findAttributeValue("license") as? PsiAnnotation ?: return null
        val name = reader.getAnnotationStringValue(licenseAnn, "name")?.takeIf { it.isNotBlank() } ?: return null
        val url = reader.getAnnotationStringValue(licenseAnn, "url")?.takeIf { it.isNotBlank() }
        return License(name, url)
    }

    // Extracts the Server list from the servers attribute of @OpenAPIDefinition or @Operation.
    fun extractServers(ann: PsiAnnotation): List<Server> =
        reader.parseAnnotationArray(ann.findAttributeValue("servers")).mapNotNull { parseServer(it) }

    // Converts the @Tag annotation on a class to a Tag model. Returns null if absent.
    fun extractClassTag(cls: PsiClass): Tag? {
        val tagAnn = cls.getAnnotation(Annotation.TAG.fqn) ?: return null
        val name = reader.getAnnotationStringValue(tagAnn, "name") ?: cls.name ?: return null
        val desc = reader.getAnnotationStringValue(tagAnn, "description")
        val externalDocs = parseExternalDoc(tagAnn, "externalDocs")
        return Tag(name, desc, externalDocs)
    }

    // Reads the URL path from the value or path attribute of a mapping annotation.
    fun getMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        return reader.getAnnotationStringValue(annotation, "value")
            ?: reader.getAnnotationStringValue(annotation, "path")
            ?: ""
    }

    // Determines the HTTP verb + path for a method.
    // Checks dedicated annotations (@GetMapping etc.) first; falls back to @RequestMapping(method=...) form.
    fun findMappingInfo(method: PsiMethod): Pair<String, String>? {
        for (mapping in HttpMapping.entries) {
            val ann = method.getAnnotation(mapping.ann.fqn) ?: continue
            return mapping.verb to getMappingPath(ann)
        }
        val rm = method.getAnnotation(Annotation.REQUEST_MAPPING.fqn) ?: return null
        val path = getMappingPath(rm)
        val methodAttr = reader.getAnnotationStringValue(rm, "method") ?: ""
        val verb = when {
            "POST" in methodAttr -> "post"
            "PUT" in methodAttr -> "put"
            "DELETE" in methodAttr -> "delete"
            "PATCH" in methodAttr -> "patch"
            else -> "get"
        }
        return verb to path
    }

    // internal: also used by OperationParser for the @Server nested inside @Link
    internal fun parseServer(ann: PsiAnnotation): Server? {
        val url = reader.getAnnotationStringValue(ann, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = reader.getAnnotationStringValue(ann, "description")
        val variables = parseServerVariables(ann.findAttributeValue("variables"))
        return Server(url, desc, variables)
    }

    // Converts a @ServerVariable array to a name → ServerVariable map.
    private fun parseServerVariables(attr: PsiAnnotationMemberValue?): Map<String, ServerVariable> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val defaultValue = reader.getAnnotationStringValue(ann, "defaultValue") ?: ""
            val desc = reader.getAnnotationStringValue(ann, "description")
            val allowableValues = reader.getAnnotationStringArray(ann, "allowableValues")
            name to ServerVariable(allowableValues, defaultValue, desc)
        }.toMap()

    // Extracts @ExternalDocumentation from the given attribute of an annotation. Returns null if url is missing.
    private fun parseExternalDoc(ann: PsiAnnotation?, attribute: String): ExternalDocumentation? {
        if (ann == null) return null
        val extDocAnn = ann.findAttributeValue(attribute) as? PsiAnnotation ?: return null
        val url = reader.getAnnotationStringValue(extDocAnn, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = reader.getAnnotationStringValue(extDocAnn, "description")
        return ExternalDocumentation(desc, url)
    }
}
