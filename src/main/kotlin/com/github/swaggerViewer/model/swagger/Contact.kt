package com.github.swaggerViewer.model.swagger

/** Represents @Contact inside @Info. All fields are optional per OAS 3.0. */
data class Contact(
    val name: String? = null,
    val email: String? = null,
    val url: String? = null
)
