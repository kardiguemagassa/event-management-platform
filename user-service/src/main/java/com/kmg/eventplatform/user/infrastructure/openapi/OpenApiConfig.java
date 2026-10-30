package com.kmg.eventplatform.user.infrastructure.openapi;

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

/**
 * OpenAPI metadata plus reusable error responses following RFC 9457 (application/problem+json).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI userServiceOpenApi(ObjectProvider<BuildProperties> buildProperties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";

        return new OpenAPI()
                .info(new Info()
                        .title("User Service API")
                        .version(version)
                        .description("Application profiles of the users; identity (credentials, e-mail, roles) "
                                + "is managed by Keycloak. Errors follow RFC 9457 and carry a stable `code`."))
                .addSecurityItem(PlatformOpenApi.requirement())
                .components(new Components()
                        .addSecuritySchemes(PlatformOpenApi.SECURITY_SCHEME, PlatformOpenApi.keycloak(issuerUri))
                        .addSchemas(PlatformOpenApi.PROBLEM_SCHEMA, problemDetailSchema())
                        .addResponses("BadRequest", PlatformOpenApi.problem(
                                "Invalid request (validation, malformed JSON, invalid sort, no e-mail in the token)",
                                withErrors(PlatformOpenApi.problemExample(400, "Validation failed",
                                        "One or more fields are invalid", "/api/users/me", "VALIDATION_FAILED"),
                                        "fullName", "must not be blank")))
                        .addResponses("NotFound", PlatformOpenApi.problem(
                                "Profile not found (not saved yet, or deleted)",
                                PlatformOpenApi.problemExample(404, "User not found",
                                        "User 4b5aa969-… not found", "/api/users/me", "USER_NOT_FOUND")))
                        .addResponses("Conflict", PlatformOpenApi.problem(
                                "Conflict (email already used, concurrent modification)",
                                PlatformOpenApi.problemExample(409, "Email already used",
                                        "Email jane@example.com is already used", "/api/users/me",
                                        "EMAIL_ALREADY_USED"))));
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
                .addProperty("title", new StringSchema().example("User not found"))
                .addProperty("status", new IntegerSchema().example(404))
                .addProperty("detail", new StringSchema().example("User 3f2b8c1e-… not found"))
                .addProperty("instance", new StringSchema().example("/api/users/3f2b8c1e-…"))
                .addProperty("code", new StringSchema().example("USER_NOT_FOUND")
                        .description("Stable machine-readable error code"));
    }
}
