package com.example.swaggerViewer.model

/** 파싱 대상 어노테이션의 FQN(패키지 포함 전체 클래스명) 목록 */
enum class Annotation(val fqn: String) {

    // Spring MVC 컨트롤러 식별용
    REST_CONTROLLER("org.springframework.web.bind.annotation.RestController"),
    CONTROLLER("org.springframework.web.bind.annotation.Controller"),

    // HTTP 메서드 매핑
    REQUEST_MAPPING("org.springframework.web.bind.annotation.RequestMapping"),
    GET_MAPPING("org.springframework.web.bind.annotation.GetMapping"),
    POST_MAPPING("org.springframework.web.bind.annotation.PostMapping"),
    PUT_MAPPING("org.springframework.web.bind.annotation.PutMapping"),
    DELETE_MAPPING("org.springframework.web.bind.annotation.DeleteMapping"),
    PATCH_MAPPING("org.springframework.web.bind.annotation.PatchMapping"),

    // Spring 파라미터 바인딩
    PATH_VARIABLE("org.springframework.web.bind.annotation.PathVariable"),
    REQUEST_PARAM("org.springframework.web.bind.annotation.RequestParam"),
    REQUEST_BODY("org.springframework.web.bind.annotation.RequestBody"),

    // Swagger/OpenAPI 전역 정의
    OPEN_API_DEFINITION("io.swagger.v3.oas.annotations.OpenAPIDefinition"),

    // Swagger/OpenAPI 문서화 — Operation
    OPERATION("io.swagger.v3.oas.annotations.Operation"),
    HIDDEN("io.swagger.v3.oas.annotations.Hidden"),

    // Swagger/OpenAPI 문서화 — Tag
    TAG("io.swagger.v3.oas.annotations.tags.Tag"),

    // Swagger/OpenAPI 문서화 — 응답
    API_RESPONSE("io.swagger.v3.oas.annotations.responses.ApiResponse"),
    API_RESPONSES("io.swagger.v3.oas.annotations.responses.ApiResponses"),

    // Swagger/OpenAPI 문서화 — 파라미터
    PARAMETER("io.swagger.v3.oas.annotations.Parameter"),
    PARAMETERS("io.swagger.v3.oas.annotations.Parameters"),

    // Swagger/OpenAPI 문서화 — 보안
    SECURITY_REQUIREMENT("io.swagger.v3.oas.annotations.security.SecurityRequirement"),

}
