package com.kmg.eventplatform.event.infrastructure.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata plus reusable error responses following RFC 9457 (application/problem+json). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/ProblemDetail";

    @Bean
    OpenAPI eventServiceOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";

        return new OpenAPI()
                .info(new Info()
                        .title("Event Service API")
                        .version(version)
                        .description("Manages events, their lifecycle and capacity. "
                                + "Errors follow RFC 9457 and carry a stable `code`."))
                .components(new Components()
                        .addSchemas("ProblemDetail", problemDetailSchema())
                        .addResponses("BadRequest", problemResponse(
                                "Invalid request (validation, malformed JSON, start not in future, invalid sort)"))
                        .addResponses("NotFound", problemResponse("Event not found"))
                        .addResponses("Forbidden", problemResponse(
                                "Not the organizer of this event (NOT_EVENT_ORGANIZER)"))
                        .addResponses("Conflict", problemResponse(
                                "Business conflict (invalid state, capacity below reserved, has reservations, "
                                        + "concurrent modification)")));
    }

    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Invalid event state"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().example("Cannot publish event 3f2b8c1e-… in status CANCELLED"))
                .addProperty("instance", new StringSchema().example("/api/events/3f2b8c1e-…/publish"))
                .addProperty("code", new StringSchema().example("INVALID_EVENT_STATE")
                        .description("Stable machine-readable error code"));
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF))));
    }
}
