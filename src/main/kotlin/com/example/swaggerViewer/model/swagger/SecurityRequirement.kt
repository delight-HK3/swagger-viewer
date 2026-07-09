package com.example.swaggerViewer.model.swagger

/** @SecurityRequirement(name, scopes) 하나를 표현한다. */
data class SecurityRequirement(
    val name: String,
    val scopes: List<String> = emptyList()
)
