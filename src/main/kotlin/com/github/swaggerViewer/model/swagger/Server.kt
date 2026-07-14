package com.github.swaggerViewer.model.swagger

/** Represents a single @Server. Used at both global (@OpenAPIDefinition.servers) and operation level. */
data class Server(
    val url: String,
    val description: String? = null,
    /** @ServerVariable list → variableName to ServerVariable */
    val variables: Map<String, ServerVariable> = emptyMap()
)
