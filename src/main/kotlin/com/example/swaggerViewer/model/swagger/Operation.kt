package com.example.swaggerViewer.model.swagger

/**
 * HTTP 엔드포인트 하나(메서드 단위)의 메타데이터를 표현한다.
 *
 * 생산: [com.example.swaggerViewer.service.PsiSwaggerScanner]
 *   - @Operation, @ApiResponse(s), @Parameter(s), @Hidden, @SecurityRequirement 등을
 *     PSI로 파싱해 조합한다.
 *   - summary 가 없으면 메서드명을 fallback으로 사용한다.
 *
 * 소비: [com.example.swaggerViewer.service.OpenApiSpecBuilder]
 *   - OpenAPI 3.0 paths.[path].[httpMethod] 객체로 직렬화된다.
 *
 * @property tags        이 operation이 속하는 태그 이름 목록. 클래스 레벨 @Tag에서 상속된다.
 * @property responses   responseCode → ApiResponseInfo 맵. 비어있으면 기본값 "200" → "OK"가 들어간다.
 * @property security    operation 레벨 보안 요구사항. 비어있으면 클래스 레벨 @SecurityRequirement를 상속.
 */
data class Operation(
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val operationId: String?,
    val deprecated: Boolean = false,
    /** @Hidden 또는 @Operation(hidden = true) — true이면 Swagger UI에서 숨긴다. */
    val hidden: Boolean = false,
    val externalDocs: ExternalDocumentation? = null,
    val parameters: List<Parameter>,
    val requestBody: RequestBody? = null,
    val responses: Map<String, ApiResponse>,
    val security: List<SecurityRequirement> = emptyList(),
    /** operation 레벨 서버 목록 (@Operation.servers). 전역 서버를 재정의한다. */
    val servers: List<Server> = emptyList()
)
