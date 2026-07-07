package com.example.swaggerViewer.model

/** @Server 하나를 표현한다. 전역(@OpenAPIDefinition.servers)과 operation 레벨 모두 사용. */
data class ServerInfo(
    val url: String,
    val description: String? = null,
    /** @ServerVariable 목록 → variableName to ServerVariableInfo */
    val variables: Map<String, ServerVariableInfo> = emptyMap()
)
