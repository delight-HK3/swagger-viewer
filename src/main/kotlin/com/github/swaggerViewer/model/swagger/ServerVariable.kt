package com.github.swaggerViewer.model.swagger

/** Represents a single @ServerVariable. Used as a value in Server.variables. */
data class ServerVariable(
    val allowableValues: List<String> = emptyList(),
    val defaultValue: String,
    val description: String? = null
)
