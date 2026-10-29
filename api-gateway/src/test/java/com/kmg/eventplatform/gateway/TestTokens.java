package com.kmg.eventplatform.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Access tokens signed like Keycloak's (RS256), with the public key served as a JWK set by a real HTTP server:
 * the gateway validates them through its actual configuration (JWK set URI, issuer, audience, expiry).
 */
final class TestTokens {

    /** Default issuer of application.yml, i.e. the public URL of the local Keycloak. */
    static final String ISSUER = "http://localhost:8180/realms/event-platform";
    static final String AUDIENCE = "event-platform-api";

    private static final RSAKey SIGNING_KEY = generateKey("platform-key");
    private static final RSAKey UNKNOWN_KEY = generateKey("unknown-key");
    private static final HttpServer JWK_SET_SERVER = startJwkSetServer();

    private TestTokens() {
    }

    /** Value of {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} for the tests. */
    static String jwkSetUri() {
        return "http://localhost:" + JWK_SET_SERVER.getAddress().getPort() + "/certs";
    }

    static String valid() {
        return sign(claims(), SIGNING_KEY);
    }

    static String expired() {
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        return sign(claims().issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(300))), SIGNING_KEY);
    }

    static String fromAnotherIssuer() {
        return sign(claims().issuer("http://localhost:8180/realms/master"), SIGNING_KEY);
    }

    static String forAnotherAudience() {
        return sign(claims().audience("account"), SIGNING_KEY);
    }

    static String signedByUnknownKey() {
        return sign(claims(), UNKNOWN_KEY);
    }

    private static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("6f1c2b8e-4a8d-4c5e-9a43-2f6d2f0b7c11")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
    }

    private static String sign(JWTClaimsSet.Builder claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwkSetServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
