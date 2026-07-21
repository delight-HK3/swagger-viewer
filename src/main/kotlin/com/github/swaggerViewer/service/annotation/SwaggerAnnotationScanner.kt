package com.github.swaggerViewer.service.annotation

import com.github.swaggerViewer.model.Annotation
import com.github.swaggerViewer.model.ScanResult
import com.github.swaggerViewer.model.swagger.OasSecurityScheme
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.Tag
import com.github.swaggerViewer.service.annotation.parser.CallbackParser
import com.github.swaggerViewer.service.annotation.parser.ExtensionParser
import com.github.swaggerViewer.service.annotation.parser.GlobalInfoParser
import com.github.swaggerViewer.service.annotation.parser.OperationParser
import com.github.swaggerViewer.service.annotation.parser.ParameterParser
import com.github.swaggerViewer.service.annotation.parser.ResponseParser
import com.github.swaggerViewer.service.annotation.parser.SchemaAnnotationParser
import com.github.swaggerViewer.service.annotation.parser.SecurityParser

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * [step 04-A] PSI scanner — traverses the entire project's PSI tree to locate every
 * Spring MVC controller and Swagger annotation, then assembles a [ScanResult].
 *
 * ## How classes are located
 * Uses [com.intellij.psi.search.searches.AnnotatedElementsSearch] for index-based lookups
 * rather than scanning files one by one. Two sources are merged:
 *  - Classes annotated with `@RestController` / `@Controller`
 *  - Classes whose methods carry `@Operation` (catches non-controller classes)
 *
 * ## Parsing delegation
 * Once a target [com.intellij.psi.PsiClass] / [com.intellij.psi.PsiMethod] is located,
 * parsing is fully delegated to the `parser` subpackage [step 05-A-*]:
 *  - [GlobalInfoParser] [step 05-A-2]  — `@OpenAPIDefinition`, `@Server`, `@Tag`, Spring routing
 *  - [SecurityParser] [step 05-A-3]    — `@SecurityScheme`, `@SecurityRequirement`
 *  - [OperationParser] [step 05-A-9]   — `@Operation` and all nested annotations
 *
 * ## Schema collection
 * After all operations are collected, [PsiSchemaAnalyzer] [step 06-A] is invoked with the
 * set of class names referenced via `@Schema(implementation = ...)`.
 *
 * Must be called inside a ReadAction.
 *
 * @see OperationParser
 * @see PsiSchemaAnalyzer
 */
class SwaggerAnnotationScanner(private val project: Project) {

    private val reader = AnnotationValueReader()
    private val globalParser = GlobalInfoParser(reader)
    private val securityParser = SecurityParser(reader)
    private val schemaParser = SchemaAnnotationParser(reader)
    private val extensionParser = ExtensionParser(reader)
    private val parameterParser = ParameterParser(reader, schemaParser)
    private val responseParser = ResponseParser(reader, globalParser, schemaParser)
    private val callbackParser = CallbackParser(reader, schemaParser, parameterParser, responseParser, extensionParser, securityParser, globalParser)
    private val operationParser = OperationParser(reader, securityParser, globalParser, schemaParser, parameterParser, responseParser, callbackParser, extensionParser)

    fun scan(): ScanResult {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Step 1: Extract global info (title, version, description, contact, license, servers) from @OpenAPIDefinition
        val openApiAnn = findOpenApiDefinitionAnnotation(facade, allScope, scope)
        val infoAnn = openApiAnn?.findAttributeValue("info") as? PsiAnnotation
        val title = infoAnn?.let { reader.getAnnotationStringValue(it, "title") } ?: project.name
        val version = infoAnn?.let { reader.getAnnotationStringValue(it, "version") }?.takeIf { it.isNotBlank() } ?: "1.0.0"
        val description = infoAnn?.let { reader.getAnnotationStringValue(it, "description") }
        val contact = infoAnn?.let { globalParser.parseContactFromInfo(it) }
        val license = infoAnn?.let { globalParser.parseLicenseFromInfo(it) }
        val servers = openApiAnn?.let { globalParser.extractServers(it) } ?: emptyList()
        val securitySchemes = collectSecuritySchemes(facade, allScope, scope)

        // Step 2: Collect target classes (@RestController / @Controller / classes with @Operation)
        val targetClasses = collectTargetClasses(facade, scope, allScope)

        // Step 3: Extract tag and path info from each class
        val tags = mutableListOf<Tag>()
        val paths = mutableMapOf<String, MutableMap<String, Operation>>()
        for (cls in targetClasses) {
            processClass(cls, tags, paths)
        }

        val referencedClasses = collectReferencedClasses(paths)
        val schemas = if (referencedClasses.isNotEmpty())
            PsiSchemaAnalyzer(project).analyze(referencedClasses)
        else emptyMap()

        return ScanResult(
            title = title, version = version, description = description,
            contact = contact, license = license,
            servers = servers, tags = tags, paths = paths,
            schemas = schemas, securitySchemes = securitySchemes
        )
    }

    // Finds the class annotated with @OpenAPIDefinition in the project and returns the annotation.
    private fun findOpenApiDefinitionAnnotation(
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): PsiAnnotation? {
        val annClass = facade.findClass(Annotation.OPEN_API_DEFINITION.fqn, allScope) ?: return null
        val configClass = AnnotatedElementsSearch.searchPsiClasses(annClass, projectScope).findFirst() ?: return null
        return configClass.getAnnotation(Annotation.OPEN_API_DEFINITION.fqn)
    }

    // Collects all classes eligible for preview.
    // Includes @RestController / @Controller classes, plus any class whose method has @Operation (deduped).
    private fun collectTargetClasses(
        facade: JavaPsiFacade,
        scope: GlobalSearchScope,
        allScope: GlobalSearchScope
    ): List<PsiClass> {
        val controllerClasses =
            findAnnotatedClasses(Annotation.REST_CONTROLLER.fqn, facade, allScope, scope) +
            findAnnotatedClasses(Annotation.CONTROLLER.fqn, facade, allScope, scope)

        val controllerQNames = controllerClasses.mapNotNull { it.qualifiedName }.toHashSet()
        val operationAnnotation = facade.findClass(Annotation.OPERATION.fqn, allScope)
        val extraClasses: List<PsiClass> = if (operationAnnotation != null) {
            AnnotatedElementsSearch.searchPsiMethods(operationAnnotation, scope).findAll()
                .mapNotNull { it.containingClass }
                .filter { it.qualifiedName !in controllerQNames }
                .distinctBy { it.qualifiedName }
        } else emptyList()

        return controllerClasses + extraClasses
    }

    // Traverses a single class and populates tags/paths with tag and path info.
    private fun processClass(
        cls: PsiClass,
        tags: MutableList<Tag>,
        paths: MutableMap<String, MutableMap<String, Operation>>
    ) {
        val basePath = globalParser.getMappingPath(cls.getAnnotation(Annotation.REQUEST_MAPPING.fqn))
        val classTag = globalParser.extractClassTag(cls)
        if (classTag != null) tags.add(classTag)
        val defaultTags = if (classTag != null) listOf(classTag.name) else listOf(cls.name ?: "Controller")
        val classSecurity = securityParser.extractClassSecurityRequirements(cls)

        for (method in cls.methods) {
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
            val (httpVerb, methodPath) = globalParser.findMappingInfo(method) ?: continue
            val fullPath = normalizePath("$basePath$methodPath")
            paths.getOrPut(fullPath) { mutableMapOf() }[httpVerb] =
                operationParser.buildOperation(method, defaultTags, classSecurity)
        }
    }

    // Collects the simple class names of all types referenced via @Schema(implementation=...) across all operations.
    private fun collectReferencedClasses(paths: Map<String, Map<String, Operation>>): Set<String> {
        val names = mutableSetOf<String>()
        for ((_, ops) in paths) {
            for ((_, op) in ops) collectFromOperation(op, names)
        }
        return names
    }

    private fun collectFromOperation(op: Operation, names: MutableSet<String>) {
        op.requestBody?.contentSchema?.let { names.add(it) }
        op.responses.values.forEach { r -> r.contentSchema?.let { names.add(it) } }
        op.callbacks.values.forEach { cb -> collectFromOperation(cb.operation, names) }
    }

    // Returns all classes annotated with the given FQN within the project scope.
    private fun findAnnotatedClasses(
        fqn: String,
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): List<PsiClass> {
        val annotationClass = facade.findClass(fqn, allScope) ?: return emptyList()
        return AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll().toList()
    }

    // Collects all @SecurityScheme and @SecuritySchemes definitions from the project.
    // Handles both the single annotation and the repeatable container form.
    // When the same name appears in both, @SecurityScheme (single) wins.
    private fun collectSecuritySchemes(
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): Map<String, OasSecurityScheme> {
        val result = mutableMapOf<String, OasSecurityScheme>()

        // @SecuritySchemes container (processed first so single @SecurityScheme takes priority via putIfAbsent)
        val multiAnnClass = facade.findClass(Annotation.SECURITY_SCHEMES.fqn, allScope)
        if (multiAnnClass != null) {
            for (cls in AnnotatedElementsSearch.searchPsiClasses(multiAnnClass, projectScope).findAll()) {
                val ann = cls.getAnnotation(Annotation.SECURITY_SCHEMES.fqn) ?: continue
                securityParser.extractSecuritySchemesFromContainer(ann).forEach { (name, scheme) ->
                    result.putIfAbsent(name, scheme)
                }
            }
        }

        // Single @SecurityScheme
        val singleAnnClass = facade.findClass(Annotation.SECURITY_SCHEME.fqn, allScope)
        if (singleAnnClass != null) {
            for (cls in AnnotatedElementsSearch.searchPsiClasses(singleAnnClass, projectScope).findAll()) {
                val ann = cls.getAnnotation(Annotation.SECURITY_SCHEME.fqn) ?: continue
                val (name, scheme) = securityParser.parseSecuritySchemeAnnotation(ann) ?: continue
                result[name] = scheme
            }
        }

        return result
    }

    // Normalizes a path to start with /, removes duplicate slashes, and strips trailing slashes.
    private fun normalizePath(path: String): String {
        var result = "/$path".replace("//", "/")
        if (result.length > 1 && result.endsWith("/")) result = result.dropLast(1)
        return result
    }
}
