package com.kmg.eventplatform.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Imported by the security configuration of each servlet service ({@code @Import}): an explicit opt-in rather
 * than an auto-configuration, so that slice tests importing that configuration get exactly the same beans.
 * Token validation itself (JWK set URI, issuer, audience) is configured by each service through the standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformSecurityConfiguration {

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoles());
        return converter;
    }

    @Bean
    SecurityProblemAdvice securityProblemAdvice() {
        return new SecurityProblemAdvice();
    }

    @Bean
    SecurityProblemHandler securityProblemHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new SecurityProblemHandler(resolver);
    }

    @Bean
    PlatformHttpSecurity platformHttpSecurity(SecurityProblemHandler problems,
                                              JwtAuthenticationConverter jwtAuthenticationConverter) {
        return new PlatformHttpSecurity(problems, jwtAuthenticationConverter);
    }
}
