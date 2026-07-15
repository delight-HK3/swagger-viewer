package com.github.swaggerViewer.model

import com.github.swaggerViewer.model.swagger.Contact
import com.github.swaggerViewer.model.swagger.License
import com.github.swaggerViewer.model.swagger.OasSecurityScheme
import com.github.swaggerViewer.model.swagger.Operation
import com.github.swaggerViewer.model.swagger.SchemaDefinition
import com.github.swaggerViewer.model.swagger.Server
import com.github.swaggerViewer.model.swagger.Tag

/**
 * Boundary DTO carrying the full PSI scan result for a project,
 * passed from [com.github.swaggerViewer.service.annotation.SwaggerAnnotationScanner] to
 * [com.github.swaggerViewer.service.annotation.SwaggerAnnotationSerializer].
 *
 * @property title           OpenAPI info.title. Falls back to project.name if @OpenAPIDefinition is absent.
 * @property version         OpenAPI info.version. Falls back to "1.0.0" if @OpenAPIDefinition is absent.
 * @property description     OpenAPI info.description. Extracted from @OpenAPIDefinition.info.description.
 * @property contact         OpenAPI info.contact. Extracted from @OpenAPIDefinition.info.contact.
 * @property license         OpenAPI info.license. Extracted from @OpenAPIDefinition.info.license.
 * @property servers         OpenAPI servers list. Extracted from @OpenAPIDefinition.servers.
 * @property paths           Two-level map: path → (httpMethod → Operation).
 *                           Example: "/users" → { "get" → Operation(...) }
 * @property schemas         PSI-analyzed schemas for the components/schemas section.
 *                           Keyed by simple class name; built by PsiSchemaAnalyzer.
 * @property securitySchemes OpenAPI components/securitySchemes. Extracted from @SecurityScheme/@SecuritySchemes.
 */
data class ScanResult(
    val title: String,
    val version: String,
    val description: String? = null,
    val contact: Contact? = null,
    val license: License? = null,
    val servers: List<Server> = emptyList(),
    val tags: List<Tag>,
    val paths: Map<String, Map<String, Operation>>,
    val schemas: Map<String, SchemaDefinition> = emptyMap(),
    val securitySchemes: Map<String, OasSecurityScheme> = emptyMap()
)
