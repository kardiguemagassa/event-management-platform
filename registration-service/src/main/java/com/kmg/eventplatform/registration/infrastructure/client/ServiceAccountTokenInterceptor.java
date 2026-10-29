package com.kmg.eventplatform.registration.infrastructure.client;

import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Adds the access token of the service account (client credentials) to each outgoing call. The token is the
 * service's own identity, never the end user's: it is cached and renewed shortly before it expires by the
 * {@link OAuth2AuthorizedClientManager}, so Keycloak is called once per token lifetime, not once per request.
 */
class ServiceAccountTokenInterceptor implements ClientHttpRequestInterceptor {

    static final String REGISTRATION_ID = "event-platform";
    private static final String PRINCIPAL = "registration-service";

    private final OAuth2AuthorizedClientManager clients;
    private final OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
            .withClientRegistrationId(REGISTRATION_ID)
            .principal(PRINCIPAL)
            .build();

    ServiceAccountTokenInterceptor(OAuth2AuthorizedClientManager clients) {
        this.clients = clients;
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, byte @NonNull [] body, @NonNull ClientHttpRequestExecution execution)
            throws IOException {
        OAuth2AuthorizedClient client = clients.authorize(authorizeRequest);
        if (client == null) {
            throw new ClientAuthorizationException(new OAuth2Error("unauthorized_client"), REGISTRATION_ID);
        }
        request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
