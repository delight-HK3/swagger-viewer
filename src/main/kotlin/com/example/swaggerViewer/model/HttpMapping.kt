package com.example.swaggerViewer.model

/** HTTP 메서드 전용 매핑 어노테이션과 OpenAPI verb를 1:1로 묶음 */
enum class HttpMapping(val ann: Annotation, val verb: String) {
    GET(Annotation.GET_MAPPING, "get"),
    POST(Annotation.POST_MAPPING, "post"),
    PUT(Annotation.PUT_MAPPING, "put"),
    DELETE(Annotation.DELETE_MAPPING, "delete"),
    PATCH(Annotation.PATCH_MAPPING, "patch"),
}
