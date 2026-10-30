package com.kmg.eventplatform.event.infrastructure.openapi;

import com.kmg.eventplatform.security.PlatformOpenApi;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata plus reusable error responses following RFC 9457 (application/problem+json). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI eventServiceOpenApi(ObjectProvider<BuildProperties> buildProperties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";

        return new OpenAPI()
                .info(new Info()
                        .title("Event Service API")
                        .version(version)
                        .description("Manages events, their lifecycle and capacity. "
                                + "Errors follow RFC 9457 and carry a stable `code`."))
                .addSecurityItem(PlatformOpenApi.requirement())
                .components(new Components()
                        .addSecuritySchemes(PlatformOpenApi.SECURITY_SCHEME, PlatformOpenApi.keycloak(issuerUri))
                        .addSchemas(PlatformOpenApi.PROBLEM_SCHEMA, problemDetailSchema())
                        .addResponses("BadRequest", PlatformOpenApi.problem(
                                "Invalid request (validation, malformed JSON, start not in future, invalid sort)",
                                withErrors(PlatformOpenApi.problemExample(400, "Validation failed",
                                        "One or more fields are invalid", "/api/events", "VALIDATION_FAILED"),
                                        "capacity", "must be greater than or equal to 1")))
                        .addResponses("NotFound", PlatformOpenApi.problem("Event not found",
                                PlatformOpenApi.problemExample(404, "Event not found",
                                        "Event 3f2b8c1e-… not found", "/api/events/3f2b8c1e-…", "EVENT_NOT_FOUND")))
                        .addResponses("Forbidden", PlatformOpenApi.problem(
                                "Not the organizer of this event (NOT_EVENT_ORGANIZER)",
                                PlatformOpenApi.problemExample(403, "Not the event organizer",
                                        "You are not the organizer of event 3f2b8c1e-…", "/api/events/3f2b8c1e-…",
                                        "NOT_EVENT_ORGANIZER")))
                        .addResponses("Conflict", PlatformOpenApi.problem(
                                "Business conflict (invalid state, capacity below reserved, has reservations, "
                                        + "concurrent modification)",
                                PlatformOpenApi.problemExample(409, "Invalid event state",
                                        "Cannot publish event 3f2b8c1e-… in status CANCELLED",
                                        "/api/events/3f2b8c1e-…/publish", "INVALID_EVENT_STATE"))));
    }

    /** 401 on every operation and the shared 403 response (platform-security). */
    @Bean
    OpenApiCustomizer securityErrors() {
        return PlatformOpenApi::documentSecurityErrors;
    }

    private static Map<String, Object> withErrors(Map<String, Object> example, String field, String message) {
        example.put("errors", Map.of(field, message));
        return example;
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
}
