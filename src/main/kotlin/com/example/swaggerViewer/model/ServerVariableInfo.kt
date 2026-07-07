package com.example.swaggerViewer.model

/** @ServerVariable 하나를 표현한다. ServerInfo.variables 맵의 값으로 사용된다. */
data class ServerVariableInfo(
    val allowableValues: List<String> = emptyList(),
    val defaultValue: String,
    val description: String? = null
)
