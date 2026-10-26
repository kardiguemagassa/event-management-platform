package com.kmg.eventplatform.user.infrastructure.openapi;

/** References to the reusable responses declared in {@link OpenApiConfig}. */
public final class OpenApiResponses {

    public static final String BAD_REQUEST = "#/components/responses/BadRequest";
    public static final String NOT_FOUND = "#/components/responses/NotFound";
    public static final String CONFLICT = "#/components/responses/Conflict";

    private OpenApiResponses() {
    }
}
