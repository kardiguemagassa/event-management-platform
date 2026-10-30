package com.kmg.eventplatform.registration.infrastructure.openapi;

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
    OpenAPI registrationServiceOpenApi(ObjectProvider<BuildProperties> buildProperties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";

        return new OpenAPI()
                .info(new Info()
                        .title("Registration Service API")
                        .version(version)
                        .description("Registers the authenticated user to events of event-service. "
                                + "Errors follow RFC 9457 and carry a stable `code`."))
                .addSecurityItem(PlatformOpenApi.requirement())
                .components(new Components()
                        .addSecuritySchemes(PlatformOpenApi.SECURITY_SCHEME, PlatformOpenApi.keycloak(issuerUri))
                        .addSchemas(PlatformOpenApi.PROBLEM_SCHEMA, problemDetailSchema())
                        .addResponses("BadRequest", PlatformOpenApi.problem(
                                "Invalid request (validation, malformed JSON or id, invalid sort)",
                                withErrors(PlatformOpenApi.problemExample(400, "Bad Request",
                                        "Invalid request content.", "/api/registrations", "VALIDATION_FAILED"),
                                        "eventId", "must not be null")))
                        .addResponses("NotFound", PlatformOpenApi.problem(
                                "Event not found, or registration not found among the caller's own",
                                PlatformOpenApi.problemExample(404, "Not Found", "Registration 7c9e6679-… not found",
                                        "/api/registrations/7c9e6679-…", "REGISTRATION_NOT_FOUND")))
                        .addResponses("Forbidden", PlatformOpenApi.problem(
                                "Not the organizer of the event (NOT_EVENT_ORGANIZER)",
                                PlatformOpenApi.problemExample(403, "Forbidden",
                                        "You are not the organizer of event 3f2b8c1e-…",
                                        "/api/registrations/events/3f2b8c1e-…", "NOT_EVENT_ORGANIZER")))
                        .addResponses("Conflict", PlatformOpenApi.problem(
                                "Business conflict (event full or not registrable, already registered, "
                                        + "already cancelled, concurrent modification)",
                                PlatformOpenApi.problemExample(409, "Conflict", "Event 3f2b8c1e-… is full",
                                        "/api/registrations", "EVENT_FULL")))
                        .addResponses("ServiceUnavailable", PlatformOpenApi.problem(
                                "A dependent service is unavailable; the request can be retried",
                                PlatformOpenApi.problemExample(503, "Service Unavailable",
                                        "A dependent service is temporarily unavailable, please retry later",
                                        "/api/registrations", "DOWNSTREAM_UNAVAILABLE"))));
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
                .addProperty("title", new StringSchema().example("Conflict"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().example("Event 3f2b8c1e-… has no seat left"))
                .addProperty("instance", new StringSchema().example("/api/registrations"))
                .addProperty("code", new StringSchema().example("EVENT_FULL")
                        .description("Stable machine-readable error code"));
    }
}
