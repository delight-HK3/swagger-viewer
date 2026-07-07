package com.example.swaggerViewer.service

import com.example.swaggerViewer.model.Annotation
import com.example.swaggerViewer.model.ExternalDocInfo
import com.example.swaggerViewer.model.HeaderInfo
import com.example.swaggerViewer.model.HttpMapping
import com.example.swaggerViewer.model.LinkInfo
import com.example.swaggerViewer.model.OperationInfo
import com.example.swaggerViewer.model.ParameterInfo
import com.example.swaggerViewer.model.RequestBodyInfo
import com.example.swaggerViewer.model.ResponseInfo
import com.example.swaggerViewer.model.ScanResult
import com.example.swaggerViewer.model.SchemaInfo
import com.example.swaggerViewer.model.SecurityRequirementInfo
import com.example.swaggerViewer.model.ServerInfo
import com.example.swaggerViewer.model.ServerVariableInfo
import com.example.swaggerViewer.model.TagInfo
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * PSI 트리 순회만으로 Spring MVC + Swagger 어노테이션을 파싱한다.
 * 런타임 실행 없이 정적 분석만 수행하며, ReadAction 컨텍스트 내에서 호출해야 한다.
 */
class PsiSwaggerScanner(private val project: Project) {

    fun scan(): ScanResult {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // 1단계: @OpenAPIDefinition에서 전역 정보(title, version, description, servers) 추출
        val openApiAnn = findOpenApiDefinitionAnnotation(facade, allScope, scope)
        val infoAnn = openApiAnn?.findAttributeValue("info") as? PsiAnnotation
        val title = infoAnn?.let { getAnnotationStringValue(it, "title") } ?: project.name
        val version = infoAnn?.let { getAnnotationStringValue(it, "version") }?.takeIf { it.isNotBlank() } ?: "1.0.0"
        val description = infoAnn?.let { getAnnotationStringValue(it, "description") }
        val servers = openApiAnn?.let { extractServers(it) } ?: emptyList()

        // 2단계: 파싱 대상 클래스 수집 (@RestController / @Controller / @Operation 보유 클래스)
        val targetClasses = collectTargetClasses(facade, scope, allScope)

        // 3단계: 각 클래스에서 태그·경로 정보 추출
        val tags = mutableListOf<TagInfo>()
        val paths = mutableMapOf<String, MutableMap<String, OperationInfo>>()
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

    /** @OpenAPIDefinition / @Operation 의 servers 속성에서 ServerInfo 목록을 추출한다. */
    private fun extractServers(ann: PsiAnnotation): List<ServerInfo> {
        return parseAnnotationArray(ann.findAttributeValue("servers")).mapNotNull { parseServer(it) }
    }

    private fun parseServer(ann: PsiAnnotation): ServerInfo? {
        val url = getAnnotationStringValue(ann, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = getAnnotationStringValue(ann, "description")
        val variables = parseServerVariables(ann.findAttributeValue("variables"))
        return ServerInfo(url, desc, variables)
    }

    private fun parseServerVariables(attr: PsiAnnotationMemberValue?): Map<String, ServerVariableInfo> {
        return parseAnnotationArray(attr).mapNotNull { ann ->
            val name = getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val defaultValue = getAnnotationStringValue(ann, "defaultValue") ?: ""
            val desc = getAnnotationStringValue(ann, "description")
            val allowableValues = getAnnotationStringArray(ann, "allowableValues")
            name to ServerVariableInfo(allowableValues, defaultValue, desc)
        }.toMap()
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

    /** 클래스 하나를 순회해 태그와 경로 정보를 tags/paths에 채운다 */
    private fun processClass(
        cls: PsiClass,
        tags: MutableList<TagInfo>,
        paths: MutableMap<String, MutableMap<String, OperationInfo>>
    ) {
        val basePath = getMappingPath(cls.getAnnotation(Annotation.REQUEST_MAPPING.fqn))
        val classTag = extractClassTag(cls)
        if (classTag != null) tags.add(classTag)
        val defaultTags = if (classTag != null) listOf(classTag.name) else listOf(cls.name ?: "Controller")
        val classSecurity = extractClassSecurityRequirements(cls)

        for (method in cls.methods) {
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
            val (httpVerb, methodPath) = findMappingInfo(method) ?: continue
            val fullPath = normalizePath("$basePath$methodPath")
            paths.getOrPut(fullPath) { mutableMapOf() }[httpVerb] =
                buildOperationInfo(method, defaultTags, classSecurity)
        }
    }

    private fun findAnnotatedClasses(
        fqn: String,
        facade: JavaPsiFacade,
        allScope: GlobalSearchScope,
        projectScope: GlobalSearchScope
    ): List<PsiClass> {
        val annotationClass = facade.findClass(fqn, allScope) ?: return emptyList()
        return AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).toList()
    }

    private fun getMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        return getAnnotationStringValue(annotation, "value")
            ?: getAnnotationStringValue(annotation, "path")
            ?: ""
    }

    private fun extractClassTag(cls: PsiClass): TagInfo? {
        val tagAnn = cls.getAnnotation(Annotation.TAG.fqn) ?: return null
        val name = getAnnotationStringValue(tagAnn, "name") ?: cls.name ?: return null
        val desc = getAnnotationStringValue(tagAnn, "description")
        val externalDocs = parseExternalDoc(tagAnn, "externalDocs")
        return TagInfo(name, desc, externalDocs)
    }

    private fun extractClassSecurityRequirements(cls: PsiClass): List<SecurityRequirementInfo> {
        val secAnn = cls.getAnnotation(Annotation.SECURITY_REQUIREMENT.fqn) ?: return emptyList()
        val name = getAnnotationStringValue(secAnn, "name") ?: return emptyList()
        val scopes = getAnnotationStringArray(secAnn, "scopes")
        return listOf(SecurityRequirementInfo(name, scopes))
    }

    private fun findMappingInfo(method: PsiMethod): Pair<String, String>? {
        // @GetMapping, @PostMapping 등 HTTP 메서드 전용 어노테이션 먼저 확인
        for (mapping in HttpMapping.entries) {
            val ann = method.getAnnotation(mapping.ann.fqn) ?: continue
            return mapping.verb to getMappingPath(ann)
        }
        // @RequestMapping(method = RequestMethod.XXX) 형태 처리
        val rm = method.getAnnotation(Annotation.REQUEST_MAPPING.fqn) ?: return null
        val path = getMappingPath(rm)
        val methodAttr = getAnnotationStringValue(rm, "method") ?: ""
        val verb = when {
            "POST" in methodAttr -> "post"
            "PUT" in methodAttr -> "put"
            "DELETE" in methodAttr -> "delete"
            "PATCH" in methodAttr -> "patch"
            else -> "get"
        }
        return verb to path
    }

    private fun buildOperationInfo(
        method: PsiMethod,
        defaultTags: List<String>,
        classSecurity: List<SecurityRequirementInfo>
    ): OperationInfo {
        val opAnn = method.getAnnotation(Annotation.OPERATION.fqn)
        val summary = getAnnotationStringValue(opAnn, "summary") ?: method.name
        val description = getAnnotationStringValue(opAnn, "description")
        val operationId = getAnnotationStringValue(opAnn, "operationId") ?: method.name
        val deprecated = getAnnotationBooleanValue(opAnn, "deprecated")
        val hidden = method.getAnnotation(Annotation.HIDDEN.fqn) != null ||
                     getAnnotationBooleanValue(opAnn, "hidden")
        val externalDocs = parseExternalDoc(opAnn, "externalDocs")

        // @Operation.tags가 있으면 우선 적용, 없으면 클래스 @Tag 상속
        val opTags = if (opAnn != null) getAnnotationStringArray(opAnn, "tags") else emptyList()
        val tags = opTags.ifEmpty { defaultTags }

        val parameters = buildParameters(method, opAnn)
        val requestBody = buildRequestBody(method, opAnn)
        val responses = buildResponses(method, opAnn)

        // operation 레벨 security가 있으면 사용, 없으면 클래스 레벨 상속
        val opSecurity = parseSecurityRequirements(opAnn, "security")
        val security = opSecurity.ifEmpty { classSecurity }

        val servers = extractServers(opAnn ?: return OperationInfo(
            summary, description, tags, operationId, deprecated, hidden, externalDocs,
            parameters, requestBody, responses, security, emptyList()
        ))

        return OperationInfo(
            summary = summary,
            description = description,
            tags = tags,
            operationId = operationId,
            deprecated = deprecated,
            hidden = hidden,
            externalDocs = externalDocs,
            parameters = parameters,
            requestBody = requestBody,
            responses = responses,
            security = security,
            servers = servers
        )
    }

    /**
     * 파라미터 목록을 조합한다.
     * 우선순위: Spring 바인딩(@PathVariable/@RequestParam) → @Parameter로 보강
     *           + @Operation.parameters / @Parameters 에서 추가 파라미터
     */
    private fun buildParameters(method: PsiMethod, opAnn: PsiAnnotation?): List<ParameterInfo> {
        // @Operation.parameters와 @Parameters에서 extra 파라미터 수집
        val extraParams = mutableMapOf<String, ParameterInfo>()
        parseAnnotationArray(opAnn?.findAttributeValue("parameters")).forEach { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@forEach
            extraParams[pi.name] = pi
        }
        method.getAnnotation(Annotation.PARAMETERS.fqn)?.let { parametersAnn ->
            parseAnnotationArray(parametersAnn.findAttributeValue("value")).forEach { ann ->
                val pi = parseParameterAnnotation(ann) ?: return@forEach
                extraParams[pi.name] = pi
            }
        }
        method.getAnnotation(Annotation.PARAMETER.fqn)?.let { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@let
            extraParams[pi.name] = pi
        }

        // Spring 바인딩 어노테이션에서 파라미터 추출, 동명 extra 파라미터는 제거
        val boundParams = mutableListOf<ParameterInfo>()
        for (param in method.parameterList.parameters) {
            // 메서드 파라미터에 직접 붙은 @Parameter로 description/example/schema 보강
            val paramAnn = param.getAnnotation(Annotation.PARAMETER.fqn)
            when {
                param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null -> continue
                param.getAnnotation(Annotation.PATH_VARIABLE.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.PATH_VARIABLE.fqn)!!
                    val name = getAnnotationStringValue(ann, "value")
                        ?: getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    boundParams.add(ParameterInfo(
                        name = name,
                        location = "path",
                        description = paramAnn?.let { getAnnotationStringValue(it, "description") },
                        required = true,
                        example = paramAnn?.let { getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
                param.getAnnotation(Annotation.REQUEST_PARAM.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.REQUEST_PARAM.fqn)!!
                    val name = getAnnotationStringValue(ann, "value")
                        ?: getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    val required = ann.findAttributeValue("required")?.text != "false"
                    boundParams.add(ParameterInfo(
                        name = name,
                        location = "query",
                        description = paramAnn?.let { getAnnotationStringValue(it, "description") },
                        required = required,
                        example = paramAnn?.let { getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
            }
        }

        return boundParams + extraParams.values
    }

    private fun parseParameterAnnotation(ann: PsiAnnotation): ParameterInfo? {
        val name = getAnnotationStringValue(ann, "name") ?: return null
        val location = getParameterLocation(ann)
        val desc = getAnnotationStringValue(ann, "description")
        val required = getAnnotationBooleanValue(ann, "required")
        val example = getAnnotationStringValue(ann, "example")
        val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
        return ParameterInfo(name, location, desc, required, example, schema)
    }

    /** @Parameter(in = ParameterIn.PATH) 등 enum 참조에서 location 문자열을 추출한다. */
    private fun getParameterLocation(ann: PsiAnnotation): String {
        val text = ann.findAttributeValue("in")?.text ?: return "query"
        return when {
            "PATH" in text -> "path"
            "HEADER" in text -> "header"
            "COOKIE" in text -> "cookie"
            else -> "query"
        }
    }

    /**
     * 요청 바디를 추출한다.
     * @Operation.requestBody(OAS 어노테이션)가 있으면 우선 적용,
     * 없으면 Spring @RequestBody 바인딩 여부만 감지한다.
     */
    private fun buildRequestBody(method: PsiMethod, opAnn: PsiAnnotation?): RequestBodyInfo? {
        val oasRbAnn = opAnn?.findAttributeValue("requestBody") as? PsiAnnotation
        if (oasRbAnn != null) {
            val desc = getAnnotationStringValue(oasRbAnn, "description")
            val required = oasRbAnn.findAttributeValue("required")?.text != "false"
            val contentSchema = extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
            return RequestBodyInfo(desc, required, contentSchema)
        }

        for (param in method.parameterList.parameters) {
            if (param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null) {
                return RequestBodyInfo(null, true, null)
            }
        }
        return null
    }

    /**
     * 응답 목록을 수집한다.
     * 우선순위: @Operation.responses → @ApiResponse(단독) → @ApiResponses
     * 동일 responseCode는 먼저 들어온 값이 유지된다.
     */
    private fun buildResponses(method: PsiMethod, opAnn: PsiAnnotation?): Map<String, ResponseInfo> {
        val result = mutableMapOf<String, ResponseInfo>()

        parseAnnotationArray(opAnn?.findAttributeValue("responses")).forEach { ann ->
            val code = getAnnotationStringValue(ann, "responseCode") ?: return@forEach
            result[code] = parseResponseInfo(ann)
        }

        method.getAnnotation(Annotation.API_RESPONSE.fqn)?.let { ann ->
            val code = getAnnotationStringValue(ann, "responseCode") ?: "200"
            result.putIfAbsent(code, parseResponseInfo(ann))
        }

        method.getAnnotation(Annotation.API_RESPONSES.fqn)?.let { ann ->
            parseAnnotationArray(ann.findAttributeValue("value")).forEach { responseAnn ->
                val code = getAnnotationStringValue(responseAnn, "responseCode") ?: return@forEach
                result.putIfAbsent(code, parseResponseInfo(responseAnn))
            }
        }

        return result.ifEmpty { mapOf("200" to ResponseInfo("OK")) }
    }

    private fun parseResponseInfo(ann: PsiAnnotation): ResponseInfo {
        val desc = getAnnotationStringValue(ann, "description") ?: "OK"
        val contentSchema = extractSchemaFromContent(ann.findAttributeValue("content"))
        val headers = parseHeaders(ann.findAttributeValue("headers"))
        val links = parseLinks(ann.findAttributeValue("links"))
        return ResponseInfo(desc, contentSchema, headers, links)
    }

    private fun parseHeaders(attr: PsiAnnotationMemberValue?): Map<String, HeaderInfo> =
        parseAnnotationArray(attr).mapNotNull { ann ->
            val name = getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = getAnnotationStringValue(ann, "description")
            val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
            name to HeaderInfo(desc, schema)
        }.toMap()

    private fun parseLinks(attr: PsiAnnotationMemberValue?): Map<String, LinkInfo> =
        parseAnnotationArray(attr).mapNotNull { ann ->
            val name = getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = getAnnotationStringValue(ann, "description")
            val opId = getAnnotationStringValue(ann, "operationId")
            val params = parseAnnotationArray(ann.findAttributeValue("parameters")).mapNotNull { paramAnn ->
                val pName = getAnnotationStringValue(paramAnn, "name") ?: return@mapNotNull null
                val expr = getAnnotationStringValue(paramAnn, "expression") ?: return@mapNotNull null
                pName to expr
            }.toMap()
            name to LinkInfo(desc, opId, params)
        }.toMap()

    private fun parseSecurityRequirements(ann: PsiAnnotation?, attribute: String): List<SecurityRequirementInfo> {
        return parseAnnotationArray(ann?.findAttributeValue(attribute)).mapNotNull { secAnn ->
            val name = getAnnotationStringValue(secAnn, "name") ?: return@mapNotNull null
            val scopes = getAnnotationStringArray(secAnn, "scopes")
            SecurityRequirementInfo(name, scopes)
        }
    }

    private fun parseExternalDoc(ann: PsiAnnotation?, attribute: String): ExternalDocInfo? {
        if (ann == null) return null
        val extDocAnn = ann.findAttributeValue(attribute) as? PsiAnnotation ?: return null
        val url = getAnnotationStringValue(extDocAnn, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = getAnnotationStringValue(extDocAnn, "description")
        return ExternalDocInfo(desc, url)
    }

    private fun parseSchemaInfo(schemaAnn: PsiAnnotation?): SchemaInfo? {
        if (schemaAnn == null) return null
        val type = getAnnotationStringValue(schemaAnn, "type")
        val format = getAnnotationStringValue(schemaAnn, "format")
        val minimum = getAnnotationStringValue(schemaAnn, "minimum")
        val maximum = getAnnotationStringValue(schemaAnn, "maximum")
        val defaultValue = getAnnotationStringValue(schemaAnn, "defaultValue")
        val allowableValues = getAnnotationStringArray(schemaAnn, "allowableValues")
        val implementation = getImplementationClassName(schemaAnn)
        if (type == null && format == null && minimum == null && maximum == null &&
            defaultValue == null && implementation == null && allowableValues.isEmpty()) return null
        return SchemaInfo(type, format, minimum, maximum, defaultValue, allowableValues, implementation)
    }

    /**
     * @Schema(implementation = Foo.class) 에서 클래스 단순명을 추출한다.
     * Void.class / void.class 는 "스키마 없음"으로 처리해 null을 반환한다.
     */
    private fun getImplementationClassName(schemaAnn: PsiAnnotation): String? {
        val impl = schemaAnn.findAttributeValue("implementation") ?: return null
        return when (impl) {
            is PsiClassObjectAccessExpression ->
                impl.operand.type.canonicalText.substringAfterLast('.').takeIf { name ->
                    name.isNotEmpty() && name != "Void" && name != "void"
                }
            else -> null
        }
    }

    /**
     * @Content(schema = @Schema(implementation = Foo.class)) 에서 클래스 단순명을 추출한다.
     * 배열인 경우 첫 번째 Content만 사용한다.
     */
    private fun extractSchemaFromContent(contentValue: PsiAnnotationMemberValue?): String? {
        val contentAnn = parseAnnotationArray(contentValue).firstOrNull() ?: return null
        val schemaAnn = contentAnn.findAttributeValue("schema") as? PsiAnnotation ?: return null
        return getImplementationClassName(schemaAnn)
    }

    // ── 공통 PSI 파싱 유틸 ────────────────────────────────────────────────────

    /** PsiAnnotationMemberValue를 PsiAnnotation 목록으로 정규화한다. */
    private fun parseAnnotationArray(attr: PsiAnnotationMemberValue?): List<PsiAnnotation> {
        if (attr == null) return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.filterIsInstance<PsiAnnotation>()
            is PsiAnnotation -> listOf(attr)
            else -> emptyList()
        }
    }

    private fun getAnnotationStringArray(ann: PsiAnnotation, attribute: String): List<String> {
        val attr = ann.findAttributeValue(attribute) ?: return emptyList()
        return when (attr) {
            is PsiArrayInitializerMemberValue -> attr.initializers.mapNotNull { extractStringValue(it) }
            else -> listOfNotNull(extractStringValue(attr))
        }
    }

    private fun getAnnotationBooleanValue(annotation: PsiAnnotation?, attribute: String): Boolean {
        if (annotation == null) return false
        val value = annotation.findAttributeValue(attribute) ?: return false
        return (value as? PsiLiteralExpression)?.value as? Boolean ?: (value.text == "true")
    }

    private fun getAnnotationStringValue(annotation: PsiAnnotation?, attribute: String): String? {
        if (annotation == null) return null
        return extractStringValue(annotation.findAttributeValue(attribute) ?: return null)
    }

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

    private fun normalizePath(path: String): String {
        var result = "/$path".replace("//", "/")
        if (result.length > 1 && result.endsWith("/")) result = result.dropLast(1)
        return result
    }
}
