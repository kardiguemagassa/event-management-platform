package com.kmg.eventplatform.registration.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

class ServiceAccountTokenInterceptorTest {

    private final OAuth2AuthorizedClientManager clients = mock(OAuth2AuthorizedClientManager.class);
    private final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    private final ServiceAccountTokenInterceptor interceptor = new ServiceAccountTokenInterceptor(clients);
    private final MockClientHttpRequest request = new MockClientHttpRequest();
    private final byte[] body = new byte[0];

    @Test
    void addsTheServiceAccountTokenToTheCall() throws Exception {
        when(clients.authorize(any())).thenReturn(authorizedClient("service-account-token"));

        interceptor.intercept(request, body, execution);

        assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer service-account-token");
        verify(execution).execute(request, body);
        ArgumentCaptor<OAuth2AuthorizeRequest> authorizeRequest = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(clients).authorize(authorizeRequest.capture());
        assertThat(authorizeRequest.getValue().getClientRegistrationId())
                .isEqualTo(ServiceAccountTokenInterceptor.REGISTRATION_ID);
        assertThat(authorizeRequest.getValue().getPrincipal().getName()).isEqualTo("registration-service");
    }

    @Test
    void neverCallsWithoutAToken() {
        assertThatThrownBy(() -> interceptor.intercept(request, body, execution))
                .isInstanceOf(ClientAuthorizationException.class);
        verifyNoInteractions(execution);
    }

    private static OAuth2AuthorizedClient authorizedClient(String tokenValue) {
        ClientRegistration registration = ClientRegistration
                .withRegistrationId(ServiceAccountTokenInterceptor.REGISTRATION_ID)
                .clientId("registration-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://localhost:8180/realms/event-platform/protocol/openid-connect/token")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(registration, "registration-service", token);
    }
}
