package com.kmg.eventplatform.registration.infrastructure.openapi;

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
    OpenAPI registrationServiceOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";

        return new OpenAPI()
                .info(new Info()
                        .title("Registration Service API")
                        .version(version)
                        .description("Registers users to events by orchestrating user-service and event-service. "
                                + "Errors follow RFC 9457 and carry a stable `code`."))
                .components(new Components()
                        .addSchemas("ProblemDetail", problemDetailSchema())
                        .addResponses("BadRequest", problemResponse(
                                "Invalid request (validation, malformed JSON or id, invalid sort)"))
                        .addResponses("NotFound", problemResponse("Registration, user or event not found"))
                        .addResponses("Conflict", problemResponse(
                                "Business conflict (event full or not registrable, already registered, "
                                        + "already cancelled, concurrent modification)"))
                        .addResponses("ServiceUnavailable", problemResponse(
                                "A dependent service is unavailable; the request can be retried")));
    }

    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Conflict"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().example("Event 3f2b8c1e-… has no seat left"))
                .addProperty("instance", new StringSchema().example("/api/registrations"))
                .addProperty("code", new StringSchema().example("EVENT_FULL")
                        .description("Stable machine-readable error code"));
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF))));
    }
}
