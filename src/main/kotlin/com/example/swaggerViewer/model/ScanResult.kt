package com.example.swaggerViewer.model

import com.example.swaggerViewer.model.swagger.Operation
import com.example.swaggerViewer.model.swagger.Server
import com.example.swaggerViewer.model.swagger.Tag

/**
 * 프로젝트 전체를 PSI 스캔한 결과물. 스캐너와 빌더 사이의 경계 DTO 역할을 한다.
 *
 * 생산: [com.example.swaggerViewer.service.PsiSwaggerScanner.scan]
 *   - 프로젝트 내 모든 @RestController / @Controller 클래스를 순회해 조합한다.
 *
 * 소비: [com.example.swaggerViewer.service.OpenApiSpecBuilder.build]
 *   - 이 객체를 OpenAPI 3.0.0 JSON 문자열로 변환한다.
 *
 * @property title       OpenAPI info.title. @OpenAPIDefinition이 없으면 project.name 사용.
 * @property version     OpenAPI info.version. @OpenAPIDefinition이 없으면 "1.0.0" 고정.
 * @property description OpenAPI info.description. @OpenAPIDefinition.info.description에서 추출.
 * @property servers     OpenAPI servers 배열. @OpenAPIDefinition.servers에서 추출.
 * @property paths       path → (httpMethod → Operation) 의 2단계 맵.
 *                       예: "/users" → { "get" → Operation(...) }
 */
data class ScanResult(
    val title: String,
    val version: String,
    val description: String? = null,
    val servers: List<Server> = emptyList(),
    val tags: List<Tag>,
    val paths: Map<String, Map<String, Operation>>
)
