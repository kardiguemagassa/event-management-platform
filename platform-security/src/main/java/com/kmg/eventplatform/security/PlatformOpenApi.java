package com.kmg.eventplatform.security;

import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * How the OpenAPI documents describe the platform's authentication: OAuth2 authorization code flow
 * against the Keycloak realm, so the Swagger UI can sign in ("Authorize") and send the access token.
 * The Swagger UI adds PKCE (public client {@code event-platform-web}, configured at the gateway).
 */
public final class PlatformOpenApi {

    /** Name of the security scheme in {@code components.securitySchemes}. */
    public static final String SECURITY_SCHEME = "keycloak";

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
}
