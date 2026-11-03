package com.kmg.eventplatform.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.net.URI;

/**
 * Backend for Frontend settings: where the browser goes after login and logout, and the Keycloak end-session
 * endpoint (RP-initiated logout), as the browser reaches them.
 */
@ConfigurationProperties("bff")
public record BffProperties(URI frontUrl, URI endSessionUri) {

    public BffProperties {
        Assert.notNull(frontUrl, "bff.front-url is required");
        Assert.notNull(endSessionUri, "bff.end-session-uri is required");
    }
}
