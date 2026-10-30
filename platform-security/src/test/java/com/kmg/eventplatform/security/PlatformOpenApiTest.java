package com.kmg.eventplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
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

    @Test
    void problemResponseCarriesItsOwnExample() {
        Map<String, Object> example = PlatformOpenApi.problemExample(404, "Event not found", "Event 42 not found",
                "/api/events/42", "EVENT_NOT_FOUND");

        ApiResponse response = PlatformOpenApi.problem("Event not found", example);

        assertThat(example).containsExactly(Map.entry("type", "about:blank"), Map.entry("title", "Event not found"),
                Map.entry("status", 404), Map.entry("detail", "Event 42 not found"),
                Map.entry("instance", "/api/events/42"), Map.entry("code", "EVENT_NOT_FOUND"));
        var mediaType = response.getContent().get(PlatformOpenApi.PROBLEM_JSON);
        assertThat(mediaType.getSchema().get$ref()).isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(mediaType.getExample()).isSameAs(example);
    }

    @Test
    void everyOperationDocumentsTheUnauthorizedResponse() {
        Operation withoutResponses = new Operation();
        Operation documented = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK"))
                .addApiResponse("401", new ApiResponse().description("Own 401")));
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/a", new PathItem().get(withoutResponses).post(documented)));

        PlatformOpenApi.documentSecurityErrors(openApi);

        assertThat(openApi.getComponents().getResponses())
                .containsOnlyKeys(PlatformOpenApi.UNAUTHORIZED, PlatformOpenApi.ACCESS_DENIED);
        assertThat(withoutResponses.getResponses().get("401").get$ref())
                .isEqualTo("#/components/responses/Unauthorized");
        assertThat(documented.getResponses().get("401").getDescription()).isEqualTo("Own 401");
        assertThat(documented.getResponses()).containsKey("200");
    }

    @Test
    void documentWithoutPathsOnlyGetsTheComponents() {
        OpenAPI openApi = new OpenAPI().components(new Components());

        PlatformOpenApi.documentSecurityErrors(openApi);

        assertThat(openApi.getComponents().getResponses()).containsKeys(PlatformOpenApi.UNAUTHORIZED);
        assertThat(openApi.getPaths()).isNull();
    }
}
