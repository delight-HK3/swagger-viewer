package com.github.swaggerViewer.model.swagger

/** Represents @ExternalDocumentation. Appears in @Operation and @Tag. */
data class ExternalDocumentation(
    val description: String?,
    val url: String
)
