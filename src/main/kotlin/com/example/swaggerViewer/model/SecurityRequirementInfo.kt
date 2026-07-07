package com.example.swaggerViewer.model

/** @SecurityRequirement(name, scopes) 하나를 표현한다. */
data class SecurityRequirementInfo(val name: String, val scopes: List<String> = emptyList())
