package com.example.swaggerViewer.model.swagger

/** @Server 하나를 표현한다. 전역(@OpenAPIDefinition.servers)과 operation 레벨 모두 사용. */
data class Server(
    val url: String,
    val description: String? = null,
    /** @ServerVariable 목록 → variableName to ServerVariableInfo */
    val variables: Map<String, ServerVariable> = emptyMap()
)
