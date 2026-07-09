package com.example.swaggerViewer.service

import com.example.swaggerViewer.model.Annotation
import com.example.swaggerViewer.model.ScanResult
import com.example.swaggerViewer.model.swagger.Operation
import com.example.swaggerViewer.model.swagger.Tag
import com.example.swaggerViewer.service.annotation.PsiAnnotationReader
import com.example.swaggerViewer.service.annotation.SwaggerModelBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * PSI 트리 순회만으로 Spring MVC + Swagger 어노테이션을 파싱한다.
 * 런타임 실행 없이 정적 분석만 수행하며, ReadAction 컨텍스트 내에서 호출해야 한다.
 *
 * 파싱 세부 구현은 두 협력 객체에 위임한다.
 *   - PsiAnnotationReader : PSI 값 읽기 (저수준 유틸)
 *   - SwaggerModelBuilder : 어노테이션 → 내부 모델 변환
 */
class PsiSwaggerScanner(private val project: Project) {

    private val reader = PsiAnnotationReader()
    private val builder = SwaggerModelBuilder(reader)

    fun scan(): ScanResult {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // 1단계: @OpenAPIDefinition에서 전역 정보(title, version, description, servers) 추출
        val openApiAnn = findOpenApiDefinitionAnnotation(facade, allScope, scope)
        val infoAnn = openApiAnn?.findAttributeValue("info") as? PsiAnnotation
        val title = infoAnn?.let { reader.getAnnotationStringValue(it, "title") } ?: project.name
        val version = infoAnn?.let { reader.getAnnotationStringValue(it, "version") }?.takeIf { it.isNotBlank() } ?: "1.0.0"
        val description = infoAnn?.let { reader.getAnnotationStringValue(it, "description") }
        val servers = openApiAnn?.let { builder.extractServers(it) } ?: emptyList()

        // 2단계: 파싱 대상 클래스 수집 (@RestController / @Controller / @Operation 보유 클래스)
        val targetClasses = collectTargetClasses(facade, scope, allScope)

        // 3단계: 각 클래스에서 태그·경로 정보 추출
        val tags = mutableListOf<Tag>()
        val paths = mutableMapOf<String, MutableMap<String, Operation>>()
        for (cls in targetClasses) {
            processClass(cls, tags, paths)
        }

        return ScanResult(title = title, version = version, description = description, servers = servers, tags = tags, paths = paths)
    }

    /** 프로젝트 내 @OpenAPIDefinition이 붙은 클래스를 찾아 해당 어노테이션을 반환한다. */
    private fun findOpenApiDefinitionAnnotation(
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): PsiAnnotation? {
        val annClass = facade.findClass(Annotation.OPEN_API_DEFINITION.fqn, allScope) ?: return null
        val configClass = AnnotatedElementsSearch.searchPsiClasses(annClass, projectScope).firstOrNull() ?: return null
        return configClass.getAnnotation(Annotation.OPEN_API_DEFINITION.fqn)
    }

    /**
     * 미리보기 대상이 되는 클래스를 모두 모은다.
     * - @RestController / @Controller가 붙은 클래스
     * - 위 어노테이션 없이 @Operation만 있는 클래스 (중복 제거)
     */
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
            AnnotatedElementsSearch.searchPsiMethods(operationAnnotation, scope).toList()
                .mapNotNull { it.containingClass }
                .filter { it.qualifiedName !in controllerQNames }
                .distinctBy { it.qualifiedName }
        } else emptyList()

        return controllerClasses + extraClasses
    }

    /** 클래스 하나를 순회해 태그와 경로 정보를 tags/paths에 채운다. */
    private fun processClass(
        cls: PsiClass,
        tags: MutableList<Tag>,
        paths: MutableMap<String, MutableMap<String, Operation>>
    ) {
        val basePath = builder.getMappingPath(cls.getAnnotation(Annotation.REQUEST_MAPPING.fqn))
        val classTag = builder.extractClassTag(cls)
        if (classTag != null) tags.add(classTag)
        val defaultTags = if (classTag != null) listOf(classTag.name) else listOf(cls.name ?: "Controller")
        val classSecurity = builder.extractClassSecurityRequirements(cls)

        for (method in cls.methods) {
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
            val (httpVerb, methodPath) = builder.findMappingInfo(method) ?: continue
            val fullPath = normalizePath("$basePath$methodPath")
            paths.getOrPut(fullPath) { mutableMapOf() }[httpVerb] =
                builder.buildOperation(method, defaultTags, classSecurity)
        }
    }

    /** 지정한 FQN 어노테이션이 붙은 클래스를 프로젝트 스코프에서 모두 찾아 반환한다. */
    private fun findAnnotatedClasses(
        fqn: String,
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): List<PsiClass> {
        val annotationClass = facade.findClass(fqn, allScope) ?: return emptyList()
        return AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).toList()
    }

    /** 경로를 /로 시작하고 중복 슬래시를 제거하며 후행 슬래시를 없앤다. */
    private fun normalizePath(path: String): String {
        var result = "/$path".replace("//", "/")
        if (result.length > 1 && result.endsWith("/")) result = result.dropLast(1)
        return result
    }
}
