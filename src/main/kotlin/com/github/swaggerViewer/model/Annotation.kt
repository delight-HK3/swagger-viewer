package com.github.swaggerViewer.model

/** FQN (fully qualified class name) registry for all annotations this plugin targets */
enum class Annotation(val fqn: String) {

    // Spring MVC controller identification
    REST_CONTROLLER("org.springframework.web.bind.annotation.RestController"),
    CONTROLLER("org.springframework.web.bind.annotation.Controller"),

    // HTTP method mapping
    REQUEST_MAPPING("org.springframework.web.bind.annotation.RequestMapping"),
    GET_MAPPING("org.springframework.web.bind.annotation.GetMapping"),
    POST_MAPPING("org.springframework.web.bind.annotation.PostMapping"),
    PUT_MAPPING("org.springframework.web.bind.annotation.PutMapping"),
    DELETE_MAPPING("org.springframework.web.bind.annotation.DeleteMapping"),
    PATCH_MAPPING("org.springframework.web.bind.annotation.PatchMapping"),

    // Spring parameter binding
    PATH_VARIABLE("org.springframework.web.bind.annotation.PathVariable"),
    REQUEST_PARAM("org.springframework.web.bind.annotation.RequestParam"),
    REQUEST_BODY("org.springframework.web.bind.annotation.RequestBody"),

    // Swagger/OpenAPI global definition
    OPEN_API_DEFINITION("io.swagger.v3.oas.annotations.OpenAPIDefinition"),

    // Swagger/OpenAPI documentation — Operation
    OPERATION("io.swagger.v3.oas.annotations.Operation"),
    HIDDEN("io.swagger.v3.oas.annotations.Hidden"),

    // Swagger/OpenAPI documentation — Tag
    TAG("io.swagger.v3.oas.annotations.tags.Tag"),

    // Swagger/OpenAPI documentation — responses
    API_RESPONSE("io.swagger.v3.oas.annotations.responses.ApiResponse"),
    API_RESPONSES("io.swagger.v3.oas.annotations.responses.ApiResponses"),

    // Swagger/OpenAPI documentation — parameters
    PARAMETER("io.swagger.v3.oas.annotations.Parameter"),
    PARAMETERS("io.swagger.v3.oas.annotations.Parameters"),

    // Swagger/OpenAPI documentation — security
    SECURITY_REQUIREMENT("io.swagger.v3.oas.annotations.security.SecurityRequirement"),
    SECURITY_REQUIREMENTS("io.swagger.v3.oas.annotations.security.SecurityRequirements"),

    // Swagger/OpenAPI documentation — callbacks
    CALLBACK("io.swagger.v3.oas.annotations.callbacks.Callback"),
    CALLBACKS("io.swagger.v3.oas.annotations.callbacks.Callbacks"),

    // Swagger/OpenAPI global security scheme definitions (components/securitySchemes)
    SECURITY_SCHEME("io.swagger.v3.oas.annotations.security.SecurityScheme"),
    SECURITY_SCHEMES("io.swagger.v3.oas.annotations.security.SecuritySchemes"),

}
