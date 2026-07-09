package com.example.swaggerViewer.model.swagger

/**
 * @Schema 어노테이션에서 추출한 스키마 메타데이터.
 * implementation이 있으면 $ref로, 나머지 필드는 인라인 스키마로 직렬화된다.
 */
data class Schema(
    val type: String? = null,
    val format: String? = null,
    val minimum: String? = null,
    val maximum: String? = null,
    val defaultValue: String? = null,
    val allowableValues: List<String> = emptyList(),
    /** @Schema(implementation = Foo.class) → "#/components/schemas/Foo" $ref */
    val implementation: String? = null
)
