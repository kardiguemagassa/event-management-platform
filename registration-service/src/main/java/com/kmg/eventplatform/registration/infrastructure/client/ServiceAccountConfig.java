package com.kmg.eventplatform.registration.infrastructure.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Client credentials outside of any user request: the authorized client is kept by the
 * {@link OAuth2AuthorizedClientService}, not in an HTTP session (the service is stateless).
 */
@Configuration(proxyBeanMethods = false)
class ServiceAccountConfig {

    @Bean
    OAuth2AuthorizedClientManager serviceAccountClientManager(ClientRegistrationRepository registrations,
                                                              OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return manager;
    }

    @Bean
    ServiceAccountTokenInterceptor serviceAccountTokenInterceptor(OAuth2AuthorizedClientManager serviceAccountClientManager) {
        return new ServiceAccountTokenInterceptor(serviceAccountClientManager);
    }
}
