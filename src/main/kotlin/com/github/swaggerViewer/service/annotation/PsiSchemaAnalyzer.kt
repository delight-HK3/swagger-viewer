package com.github.swaggerViewer.service.annotation

import com.github.swaggerViewer.model.swagger.PropertySchema
import com.github.swaggerViewer.model.swagger.SchemaDefinition
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

private const val OAS_SCHEMA = "io.swagger.v3.oas.annotations.media.Schema"
private const val NOT_NULL_JAVAX = "javax.validation.constraints.NotNull"
private const val NOT_NULL_JAKARTA = "jakarta.validation.constraints.NotNull"
private const val NOT_NULL_JB = "org.jetbrains.annotations.NotNull"

/**
 * [step 06-A] Schema analyzer — takes the set of class names collected by
 * [SwaggerAnnotationScanner] [step 04-A] and builds full [SchemaDefinition] objects
 * for the `components/schemas` section.
 *
 * ## BFS traversal
 * Starting from the root class names (referenced via `@Schema(implementation = Foo.class)`),
 * each class's fields are inspected. If a field's type is itself a project class, that class
 * is enqueued for analysis. This continues until no new classes are discovered.
 *
 * ## Field analysis
 *  - Primitive and well-known JDK types are mapped to OAS `type` + `format` pairs.
 *  - Collection / Map types produce `type: array` or `type: object` schemas.
 *  - Unknown class types produce a `$ref` pointing to `components/schemas/{ClassName}`.
 *  - Enum classes produce `type: string` with an `enum` values list.
 *  - `@NotNull` (javax / jakarta / JetBrains) on a field adds the field name to `required`.
 *  - `@Schema(description, example)` on a field enriches the property.
 *
 * Uses [com.intellij.psi.search.PsiShortNamesCache] for index-based class lookup by simple name.
 * Must be called inside a ReadAction — guaranteed by [SwaggerAnnotationScanner] [step 04-A].
 *
 * @see SwaggerAnnotationSerializer
 */
class PsiSchemaAnalyzer(project: Project) {

    private val scope = GlobalSearchScope.projectScope(project)
    private val cache = PsiShortNamesCache.getInstance(project)

    // Analyzes the given root class names and all transitively referenced classes via BFS.
    fun analyze(rootNames: Set<String>): Map<String, SchemaDefinition> {
        val result = linkedMapOf<String, SchemaDefinition>()
        val queue = ArrayDeque(rootNames.toList())

        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in result) continue

            val schema = buildSchema(name) ?: continue
            result[name] = schema

            schema.properties.values
                .flatMap { refsIn(it) }
                .filter { it !in result }
                .forEach { queue.addLast(it) }
        }

        return result
    }

    // ── Schema construction ───────────────────────────────────────────────────

    // Resolves a class by simple name and builds a SchemaDefinition from its fields.
    private fun buildSchema(simpleName: String): SchemaDefinition? {
        val psiClass = cache.getClassesByName(simpleName, scope).firstOrNull() ?: return null

        // Class-level @Schema(description = "...")
        val classDesc = psiClass.getAnnotation(OAS_SCHEMA)
            ?.let { readString(it, "description") }

        if (psiClass.isEnum) {
            val values = psiClass.fields.filterIsInstance<PsiEnumConstant>().map { it.name }
            return SchemaDefinition(description = classDesc, enumValues = values)
        }

        val properties = linkedMapOf<String, PropertySchema>()
        val required = mutableListOf<String>()

        // allFields includes inherited fields and works for both Java and Kotlin light classes.
        // Kotlin data class properties are exposed as backing fields by KtLightClass.
        for (field in psiClass.allFields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            if (field is PsiEnumConstant) continue

            val fieldSchemaAnn = field.getAnnotation(OAS_SCHEMA)
            val desc = fieldSchemaAnn?.let { readString(it, "description") }
            val example = fieldSchemaAnn?.let { readString(it, "example") }

            properties[field.name] = typeToSchema(field.type).copy(description = desc, example = example)

            if (hasNotNullAnnotation(field)) required.add(field.name)
        }

        return SchemaDefinition(description = classDesc, properties = properties, required = required)
    }

    // ── PsiType → PropertySchema ──────────────────────────────────────────────

    // Maps a PsiType to the corresponding OAS PropertySchema (type + format).
    private fun typeToSchema(type: PsiType): PropertySchema = when {
        type == PsiTypes.intType() || type == PsiTypes.shortType() || type == PsiTypes.byteType() ->
            PropertySchema(type = "integer", format = "int32")
        type == PsiTypes.longType() ->
            PropertySchema(type = "integer", format = "int64")
        type == PsiTypes.doubleType() ->
            PropertySchema(type = "number", format = "double")
        type == PsiTypes.floatType() ->
            PropertySchema(type = "number", format = "float")
        type == PsiTypes.booleanType() ->
            PropertySchema(type = "boolean")
        type is PsiArrayType ->
            PropertySchema(type = "array", items = typeToSchema(type.componentType))
        type is PsiClassType -> classTypeToSchema(type)
        else -> PropertySchema(type = "string")
    }

    // Maps a PsiClassType (boxed primitives, collections, etc.) to a PropertySchema.
    // For unknown class types, emits a $ref pointing to the simple class name.
    private fun classTypeToSchema(type: PsiClassType): PropertySchema {
        val fqn = type.canonicalText.substringBefore("<")
        return when (fqn) {
            "java.lang.String", "kotlin.String" ->
                PropertySchema(type = "string")
            "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
            "kotlin.Int", "kotlin.Short", "kotlin.Byte" ->
                PropertySchema(type = "integer", format = "int32")
            "java.lang.Long", "kotlin.Long" ->
                PropertySchema(type = "integer", format = "int64")
            "java.lang.Double", "java.math.BigDecimal", "kotlin.Double" ->
                PropertySchema(type = "number", format = "double")
            "java.lang.Float", "kotlin.Float" ->
                PropertySchema(type = "number", format = "float")
            "java.lang.Boolean", "kotlin.Boolean" ->
                PropertySchema(type = "boolean")
            "java.math.BigInteger" ->
                PropertySchema(type = "integer")
            "java.time.LocalDate" ->
                PropertySchema(type = "string", format = "date")
            "java.time.LocalDateTime", "java.time.OffsetDateTime",
            "java.time.ZonedDateTime", "java.util.Date", "java.util.Calendar" ->
                PropertySchema(type = "string", format = "date-time")
            "java.util.UUID" ->
                PropertySchema(type = "string", format = "uuid")
            "java.lang.Object", "kotlin.Any" ->
                PropertySchema(type = "object")
            in COLLECTION_FQNS -> {
                val itemType = type.parameters.firstOrNull()
                val items = if (itemType != null) typeToSchema(itemType) else PropertySchema(type = "string")
                PropertySchema(type = "array", items = items)
            }
            in MAP_FQNS -> PropertySchema(type = "object")
            else -> {
                val resolved = type.resolve()
                if (resolved?.isEnum == true) PropertySchema(type = "string")
                else PropertySchema(ref = type.className)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Returns true if the field has any @NotNull annotation (javax, jakarta, or JetBrains).
    private fun hasNotNullAnnotation(field: PsiField): Boolean =
        field.getAnnotation(NOT_NULL_JAVAX) != null ||
        field.getAnnotation(NOT_NULL_JAKARTA) != null ||
        field.getAnnotation(NOT_NULL_JB) != null

    // Collects all $ref names referenced by a PropertySchema, including nested array items.
    private fun refsIn(prop: PropertySchema): List<String> = buildList {
        if (prop.ref != null) add(prop.ref)
        if (prop.items != null) addAll(refsIn(prop.items))
    }

    // Reads a string literal from a named attribute of the given annotation.
    private fun readString(ann: PsiAnnotation, attr: String): String? {
        val value = ann.findAttributeValue(attr) ?: return null
        return (value as? PsiLiteralExpression)?.value as? String
    }

    companion object {
        private val COLLECTION_FQNS = setOf(
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.Collection",
            "kotlin.collections.List", "kotlin.collections.MutableList",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.Collection"
        )
        private val MAP_FQNS = setOf(
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "kotlin.collections.Map", "kotlin.collections.MutableMap"
        )
    }
}
