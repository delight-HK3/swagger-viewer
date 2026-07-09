package com.example.swaggerViewer.service.annotation

import com.example.swaggerViewer.model.Annotation
import com.example.swaggerViewer.model.HttpMapping
import com.example.swaggerViewer.model.swagger.ApiResponse
import com.example.swaggerViewer.model.swagger.ExternalDocumentation
import com.example.swaggerViewer.model.swagger.Header
import com.example.swaggerViewer.model.swagger.Link
import com.example.swaggerViewer.model.swagger.Operation
import com.example.swaggerViewer.model.swagger.Parameter
import com.example.swaggerViewer.model.swagger.RequestBody
import com.example.swaggerViewer.model.swagger.Schema
import com.example.swaggerViewer.model.swagger.SecurityRequirement
import com.example.swaggerViewer.model.swagger.Server
import com.example.swaggerViewer.model.swagger.ServerVariable
import com.example.swaggerViewer.model.swagger.Tag
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiMethod

/**
 * Swagger/Spring 어노테이션을 내부 모델로 변환하는 책임만 담당한다.
 * PSI 저수준 읽기는 PsiAnnotationReader에 위임한다.
 */
class SwaggerModelBuilder(private val reader: PsiAnnotationReader) {

    /** @OpenAPIDefinition / @Operation 의 servers 속성에서 Server 목록을 추출한다. */
    fun extractServers(ann: PsiAnnotation): List<Server> {
        return reader.parseAnnotationArray(ann.findAttributeValue("servers")).mapNotNull { parseServer(it) }
    }

    /** @Server 어노테이션 하나를 Server 모델로 변환한다. url이 없으면 null을 반환한다. */
    fun parseServer(ann: PsiAnnotation): Server? {
        val url = reader.getAnnotationStringValue(ann, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = reader.getAnnotationStringValue(ann, "description")
        val variables = parseServerVariables(ann.findAttributeValue("variables"))
        return Server(url, desc, variables)
    }

    /** @ServerVariable 배열을 name → ServerVariable 맵으로 변환한다. */
    private fun parseServerVariables(attr: PsiAnnotationMemberValue?): Map<String, ServerVariable> {
        return reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val defaultValue = reader.getAnnotationStringValue(ann, "defaultValue") ?: ""
            val desc = reader.getAnnotationStringValue(ann, "description")
            val allowableValues = reader.getAnnotationStringArray(ann, "allowableValues")
            name to ServerVariable(allowableValues, defaultValue, desc)
        }.toMap()
    }

    /** 매핑 어노테이션의 value 또는 path 속성에서 URL 경로를 읽는다. 없으면 빈 문자열 반환. */
    fun getMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        return reader.getAnnotationStringValue(annotation, "value")
            ?: reader.getAnnotationStringValue(annotation, "path")
            ?: ""
    }

    /** 클래스에 붙은 @Tag 어노테이션을 Tag 모델로 변환한다. 없으면 null. */
    fun extractClassTag(cls: PsiClass): Tag? {
        val tagAnn = cls.getAnnotation(Annotation.TAG.fqn) ?: return null
        val name = reader.getAnnotationStringValue(tagAnn, "name") ?: cls.name ?: return null
        val desc = reader.getAnnotationStringValue(tagAnn, "description")
        val externalDocs = parseExternalDoc(tagAnn, "externalDocs")
        return Tag(name, desc, externalDocs)
    }

    /** 클래스에 붙은 @SecurityRequirement를 추출한다. 메서드에 security가 없을 때 상속 기본값으로 사용된다. */
    fun extractClassSecurityRequirements(cls: PsiClass): List<SecurityRequirement> {
        val secAnn = cls.getAnnotation(Annotation.SECURITY_REQUIREMENT.fqn) ?: return emptyList()
        val name = reader.getAnnotationStringValue(secAnn, "name") ?: return emptyList()
        val scopes = reader.getAnnotationStringArray(secAnn, "scopes")
        return listOf(SecurityRequirement(name, scopes))
    }

    /**
     * 메서드의 HTTP verb와 경로를 결정한다.
     * @GetMapping 등 전용 어노테이션을 먼저 확인하고, 없으면 @RequestMapping(method=...) 형태를 처리한다.
     */
    fun findMappingInfo(method: PsiMethod): Pair<String, String>? {
        // @GetMapping, @PostMapping 등 HTTP 메서드 전용 어노테이션 먼저 확인
        for (mapping in HttpMapping.entries) {
            val ann = method.getAnnotation(mapping.ann.fqn) ?: continue
            return mapping.verb to getMappingPath(ann)
        }
        // @RequestMapping(method = RequestMethod.XXX) 형태 처리
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

    /** @Operation 어노테이션과 Spring 바인딩 정보를 합쳐 Operation 모델을 만든다. */
    fun buildOperation(
        method: PsiMethod,
        defaultTags: List<String>,
        classSecurity: List<SecurityRequirement>
    ): Operation {
        val opAnn = method.getAnnotation(Annotation.OPERATION.fqn)
        val summary = reader.getAnnotationStringValue(opAnn, "summary") ?: method.name
        val description = reader.getAnnotationStringValue(opAnn, "description")
        val operationId = reader.getAnnotationStringValue(opAnn, "operationId") ?: method.name
        val deprecated = reader.getAnnotationBooleanValue(opAnn, "deprecated")
        val hidden = method.getAnnotation(Annotation.HIDDEN.fqn) != null ||
                     reader.getAnnotationBooleanValue(opAnn, "hidden")
        val externalDocs = parseExternalDoc(opAnn, "externalDocs")

        // @Operation.tags가 있으면 우선 적용, 없으면 클래스 @Tag 상속
        val opTags = if (opAnn != null) reader.getAnnotationStringArray(opAnn, "tags") else emptyList()
        val tags = opTags.ifEmpty { defaultTags }

        val parameters = buildParameters(method, opAnn)
        val requestBody = buildRequestBody(method, opAnn)
        val responses = buildResponses(method, opAnn)

        // operation 레벨 security가 있으면 사용, 없으면 클래스 레벨 상속
        val opSecurity = parseSecurityRequirements(opAnn, "security")
        val security = opSecurity.ifEmpty { classSecurity }

        val servers = extractServers(opAnn ?: return Operation(
            summary, description, tags, operationId, deprecated, hidden, externalDocs,
            parameters, requestBody, responses, security, emptyList()
        ))

        return Operation(
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
    fun buildParameters(method: PsiMethod, opAnn: PsiAnnotation?): List<Parameter> {
        // @Operation.parameters와 @Parameters에서 extra 파라미터 수집
        val extraParams = mutableMapOf<String, Parameter>()
        reader.parseAnnotationArray(opAnn?.findAttributeValue("parameters")).forEach { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@forEach
            extraParams[pi.name] = pi
        }
        method.getAnnotation(Annotation.PARAMETERS.fqn)?.let { parametersAnn ->
            reader.parseAnnotationArray(parametersAnn.findAttributeValue("value")).forEach { ann ->
                val pi = parseParameterAnnotation(ann) ?: return@forEach
                extraParams[pi.name] = pi
            }
        }
        method.getAnnotation(Annotation.PARAMETER.fqn)?.let { ann ->
            val pi = parseParameterAnnotation(ann) ?: return@let
            extraParams[pi.name] = pi
        }

        // Spring 바인딩 어노테이션에서 파라미터 추출, 동명 extra 파라미터는 제거
        val boundParams = mutableListOf<Parameter>()
        for (param in method.parameterList.parameters) {
            // 메서드 파라미터에 직접 붙은 @Parameter로 description/example/schema 보강
            val paramAnn = param.getAnnotation(Annotation.PARAMETER.fqn)
            when {
                param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null -> continue
                param.getAnnotation(Annotation.PATH_VARIABLE.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.PATH_VARIABLE.fqn)!!
                    val name = reader.getAnnotationStringValue(ann, "value")
                        ?: reader.getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    boundParams.add(Parameter(
                        name = name,
                        location = "path",
                        description = paramAnn?.let { reader.getAnnotationStringValue(it, "description") },
                        required = true,
                        example = paramAnn?.let { reader.getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
                param.getAnnotation(Annotation.REQUEST_PARAM.fqn) != null -> {
                    val ann = param.getAnnotation(Annotation.REQUEST_PARAM.fqn)!!
                    val name = reader.getAnnotationStringValue(ann, "value")
                        ?: reader.getAnnotationStringValue(ann, "name")
                        ?: param.name ?: continue
                    val required = ann.findAttributeValue("required")?.text != "false"
                    boundParams.add(Parameter(
                        name = name,
                        location = "query",
                        description = paramAnn?.let { reader.getAnnotationStringValue(it, "description") },
                        required = required,
                        example = paramAnn?.let { reader.getAnnotationStringValue(it, "example") },
                        schema = paramAnn?.let { parseSchemaInfo(it.findAttributeValue("schema") as? PsiAnnotation) }
                    ))
                    extraParams.remove(name)
                }
            }
        }

        return boundParams + extraParams.values
    }

    /** @Parameter 어노테이션 하나를 Parameter 모델로 변환한다. name이 없으면 null. */
    private fun parseParameterAnnotation(ann: PsiAnnotation): Parameter? {
        val name = reader.getAnnotationStringValue(ann, "name") ?: return null
        val location = getParameterLocation(ann)
        val desc = reader.getAnnotationStringValue(ann, "description")
        val required = reader.getAnnotationBooleanValue(ann, "required")
        val example = reader.getAnnotationStringValue(ann, "example")
        val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
        return Parameter(name, location, desc, required, example, schema)
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
    fun buildRequestBody(method: PsiMethod, opAnn: PsiAnnotation?): RequestBody? {
        val oasRbAnn = opAnn?.findAttributeValue("requestBody") as? PsiAnnotation
        if (oasRbAnn != null) {
            val desc = reader.getAnnotationStringValue(oasRbAnn, "description")
            val required = oasRbAnn.findAttributeValue("required")?.text != "false"
            val contentSchema = extractSchemaFromContent(oasRbAnn.findAttributeValue("content"))
            return RequestBody(desc, required, contentSchema)
        }

        for (param in method.parameterList.parameters) {
            if (param.getAnnotation(Annotation.REQUEST_BODY.fqn) != null) {
                return RequestBody(null, true, null)
            }
        }
        return null
    }

    /**
     * 응답 목록을 수집한다.
     * 우선순위: @Operation.responses → @ApiResponse(단독) → @ApiResponses
     * 동일 responseCode는 먼저 들어온 값이 유지된다.
     */
    fun buildResponses(method: PsiMethod, opAnn: PsiAnnotation?): Map<String, ApiResponse> {
        val result = mutableMapOf<String, ApiResponse>()

        reader.parseAnnotationArray(opAnn?.findAttributeValue("responses")).forEach { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: return@forEach
            result[code] = parseApiResponse(ann)
        }

        method.getAnnotation(Annotation.API_RESPONSE.fqn)?.let { ann ->
            val code = reader.getAnnotationStringValue(ann, "responseCode") ?: "200"
            result.putIfAbsent(code, parseApiResponse(ann))
        }

        method.getAnnotation(Annotation.API_RESPONSES.fqn)?.let { ann ->
            reader.parseAnnotationArray(ann.findAttributeValue("value")).forEach { responseAnn ->
                val code = reader.getAnnotationStringValue(responseAnn, "responseCode") ?: return@forEach
                result.putIfAbsent(code, parseApiResponse(responseAnn))
            }
        }

        return result.ifEmpty { mapOf("200" to ApiResponse("OK")) }
    }

    /** @ApiResponse 어노테이션 하나를 ApiResponse 모델로 변환한다. */
    private fun parseApiResponse(ann: PsiAnnotation): ApiResponse {
        val desc = reader.getAnnotationStringValue(ann, "description") ?: "OK"
        val contentSchema = extractSchemaFromContent(ann.findAttributeValue("content"))
        val headers = parseHeaders(ann.findAttributeValue("headers"))
        val links = parseLinks(ann.findAttributeValue("links"))
        return ApiResponse(desc, contentSchema, headers, links)
    }

    /** @Header 배열을 name → Header 맵으로 변환한다. */
    private fun parseHeaders(attr: PsiAnnotationMemberValue?): Map<String, Header> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = reader.getAnnotationStringValue(ann, "description")
            val schema = parseSchemaInfo(ann.findAttributeValue("schema") as? PsiAnnotation)
            name to Header(desc, schema)
        }.toMap()

    /** @Link 배열을 name → Link 맵으로 변환한다. */
    private fun parseLinks(attr: PsiAnnotationMemberValue?): Map<String, Link> =
        reader.parseAnnotationArray(attr).mapNotNull { ann ->
            val name = reader.getAnnotationStringValue(ann, "name") ?: return@mapNotNull null
            val desc = reader.getAnnotationStringValue(ann, "description")
            val opId = reader.getAnnotationStringValue(ann, "operationId")
            val params = reader.parseAnnotationArray(ann.findAttributeValue("parameters")).mapNotNull { paramAnn ->
                val pName = reader.getAnnotationStringValue(paramAnn, "name") ?: return@mapNotNull null
                val expr = reader.getAnnotationStringValue(paramAnn, "expression") ?: return@mapNotNull null
                pName to expr
            }.toMap()
            name to Link(desc, opId, params)
        }.toMap()

    /** 지정 속성 이름에서 @SecurityRequirement 배열을 추출한다. */
    fun parseSecurityRequirements(ann: PsiAnnotation?, attribute: String): List<SecurityRequirement> {
        return reader.parseAnnotationArray(ann?.findAttributeValue(attribute)).mapNotNull { secAnn ->
            val name = reader.getAnnotationStringValue(secAnn, "name") ?: return@mapNotNull null
            val scopes = reader.getAnnotationStringArray(secAnn, "scopes")
            SecurityRequirement(name, scopes)
        }
    }

    /** 어노테이션의 지정 속성에서 @ExternalDocumentation을 추출한다. url이 없으면 null. */
    fun parseExternalDoc(ann: PsiAnnotation?, attribute: String): ExternalDocumentation? {
        if (ann == null) return null
        val extDocAnn = ann.findAttributeValue(attribute) as? PsiAnnotation ?: return null
        val url = reader.getAnnotationStringValue(extDocAnn, "url")?.takeIf { it.isNotBlank() } ?: return null
        val desc = reader.getAnnotationStringValue(extDocAnn, "description")
        return ExternalDocumentation(desc, url)
    }

    /** @Schema 어노테이션을 Schema 모델로 변환한다. 유효 속성이 하나도 없으면 null. */
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
        val contentAnn = reader.parseAnnotationArray(contentValue).firstOrNull() ?: return null
        val schemaAnn = contentAnn.findAttributeValue("schema") as? PsiAnnotation ?: return null
        return getImplementationClassName(schemaAnn)
    }
}
