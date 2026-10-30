package com.kmg.eventplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformOpenApiTest {

    private static final String REALM = "http://localhost:8180/realms/event-platform";

    @ParameterizedTest
    @ValueSource(strings = {REALM, REALM + "/"})
    void authorizationCodeFlowOfTheRealm(String issuerUri) {
        SecurityScheme scheme = PlatformOpenApi.keycloak(issuerUri);

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);
        OAuthFlow flow = scheme.getFlows().getAuthorizationCode();
        assertThat(flow.getAuthorizationUrl()).isEqualTo(REALM + "/protocol/openid-connect/auth");
        assertThat(flow.getTokenUrl()).isEqualTo(REALM + "/protocol/openid-connect/token");
        assertThat(flow.getScopes()).containsOnlyKeys("openid");
    }

    @Test
    void requirementNamesTheScheme() {
        assertThat(PlatformOpenApi.requirement()).containsOnlyKeys(PlatformOpenApi.SECURITY_SCHEME);
    }
}
