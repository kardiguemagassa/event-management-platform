package com.kmg.eventplatform.user.infrastructure.openapi;

import com.kmg.eventplatform.security.PlatformOpenApi;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata plus reusable error responses following RFC 9457 (application/problem+json).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/ProblemDetail";

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
                        .addSchemas("ProblemDetail", problemDetailSchema())
                        .addResponses("BadRequest", problemResponse(
                                "Invalid request (validation, malformed JSON, invalid sort, no e-mail in the token)"))
                        .addResponses("NotFound", problemResponse("Profile not found (not saved yet, or deleted)"))
                        .addResponses("Conflict", problemResponse("Conflict (email already used, concurrent modification)")));
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

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF))));
    }
}
