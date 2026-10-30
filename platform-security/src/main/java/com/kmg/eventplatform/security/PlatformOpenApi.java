package com.kmg.eventplatform.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the OpenAPI documents describe the platform's authentication and its errors.
 * <ul>
 *   <li>OAuth2 authorization code flow against the Keycloak realm, so the Swagger UI can sign in ("Authorize")
 *   and send the access token. The Swagger UI adds PKCE (public client {@code event-platform-web}, configured
 *   at the gateway).</li>
 *   <li>RFC 9457 error responses ({@code application/problem+json}) with an example of their own, and the
 *   {@code 401} / {@code 403} answered by {@link SecurityProblemHandler} on every operation.</li>
 * </ul>
 */
public final class PlatformOpenApi {

    /** Name of the security scheme in {@code components.securitySchemes}. */
    public static final String SECURITY_SCHEME = "keycloak";

    /** Name of the RFC 9457 schema each service declares in {@code components.schemas}. */
    public static final String PROBLEM_SCHEMA = "ProblemDetail";

    /** Reusable responses registered by {@link #documentSecurityErrors(OpenAPI)}. */
    public static final String UNAUTHORIZED = "Unauthorized";
    public static final String ACCESS_DENIED = "AccessDenied";

    static final String PROBLEM_JSON = "application/problem+json";
    private static final String RESPONSES_REF = "#/components/responses/";

    private PlatformOpenApi() {
    }

    /**
     * The Keycloak OAuth2 scheme of a realm.
     *
     * @param issuerUri issuer of the realm as the browser reaches it, e.g. {@code http://localhost:8180/realms/event-platform}
     */
    public static SecurityScheme keycloak(String issuerUri) {
        String endpoints = (issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri)
                + "/protocol/openid-connect";
        return new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Keycloak sign-in (authorization code with PKCE)")
                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                        .authorizationUrl(endpoints + "/auth")
                        .tokenUrl(endpoints + "/token")
                        .scopes(new Scopes().addString("openid", "OpenID Connect sign-in"))));
    }

    /** Requirement of the scheme, applied to the whole document: the Swagger UI sends the token on every call. */
    public static SecurityRequirement requirement() {
        return new SecurityRequirement().addList(SECURITY_SCHEME);
    }

    /**
     * Example body of an error, as the services answer it. Mutable: extra members (e.g. {@code errors}) can be added.
     */
    public static Map<String, Object> problemExample(int status, String title, String detail, String instance,
                                                     String code) {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("type", "about:blank");
        example.put("title", title);
        example.put("status", status);
        example.put("detail", detail);
        example.put("instance", instance);
        example.put("code", code);
        return example;
    }

    /** An RFC 9457 error response with its own example (Swagger UI otherwise shows the schema's single example). */
    public static ApiResponse problem(String description, Map<String, Object> example) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))
                        .example(example)));
    }

    /**
     * Registers the {@code 401} and {@code 403} responses of the resource server and adds the {@code 401} to every
     * operation: an invalid or expired token is rejected on any route, public ones included. The {@code 403}
     * ({@value #ACCESS_DENIED}) is referenced by the operations restricted to a role.
     */
    public static void documentSecurityErrors(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents()
                .addResponses(UNAUTHORIZED, problem("Missing, invalid or expired access token",
                        problemExample(401, "Unauthorized", "A valid access token is required", "/api/…",
                                "UNAUTHENTICATED")))
                .addResponses(ACCESS_DENIED, problem("The caller's roles do not allow this operation",
                        problemExample(403, "Forbidden", "You are not allowed to access this resource", "/api/…",
                                "ACCESS_DENIED")));
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .forEach(PlatformOpenApi::addUnauthorized);
    }

    private static void addUnauthorized(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        operation.getResponses().putIfAbsent("401", new ApiResponse().$ref(RESPONSES_REF + UNAUTHORIZED));
    }
}
