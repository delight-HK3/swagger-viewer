package com.example.swaggerViewer.model.swagger

/**
 * @Operation.requestBody 또는 Spring @RequestBody에서 추출한 요청 바디 정보.
 * contentSchema가 있으면 $ref로, 없으면 generic object로 직렬화된다.
 */
data class RequestBody(
    val description: String? = null,
    val required: Boolean = true,
    /** @Schema(implementation = Foo.class)에서 추출한 클래스 단순명 */
    val contentSchema: String? = null
)
