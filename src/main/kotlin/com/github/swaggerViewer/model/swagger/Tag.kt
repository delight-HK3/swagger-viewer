package com.github.swaggerViewer.model.swagger

/** Represents a single OpenAPI tag. Extracted from @Tag(name, description, externalDocs). */
data class Tag(
    val name: String,
    val description: String?,
    val externalDocs: ExternalDocumentation? = null
)
