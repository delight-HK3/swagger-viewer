package com.github.swaggerViewer.model

/** Maps each dedicated HTTP method annotation to its corresponding OpenAPI verb. */
enum class HttpMapping(val ann: Annotation, val verb: String) {
    GET(Annotation.GET_MAPPING, "get"),
    POST(Annotation.POST_MAPPING, "post"),
    PUT(Annotation.PUT_MAPPING, "put"),
    DELETE(Annotation.DELETE_MAPPING, "delete"),
    PATCH(Annotation.PATCH_MAPPING, "patch"),
}
