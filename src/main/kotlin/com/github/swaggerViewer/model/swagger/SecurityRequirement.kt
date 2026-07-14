package com.github.swaggerViewer.model.swagger

/** Represents a single @SecurityRequirement(name, scopes). */
data class SecurityRequirement(
    val name: String,
    val scopes: List<String> = emptyList()
)
