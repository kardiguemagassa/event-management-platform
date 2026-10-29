package com.kmg.eventplatform.security.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for Keycloak in tests: access tokens signed like Keycloak's (RS256, realm roles, audience), a JWK set
 * endpoint publishing the public key, and a token endpoint answering client credentials requests.
 * Services validate these tokens through their real configuration (JWK set URI, issuer, audience, expiry).
 */
public final class TestTokens {

    /** Default issuer of the services' configuration: the public URL of the local Keycloak realm. */
    public static final String ISSUER = "http://localhost:8180/realms/event-platform";
    public static final String AUDIENCE = "event-platform-api";
    /** Subject of the tokens issued by the fake token endpoint (service account of registration-service). */
    public static final UUID SERVICE_ACCOUNT = UUID.fromString("0b9b7c4e-5d1a-4c6f-9e2b-7a3d1f8c2e55");

    private static final RSAKey SIGNING_KEY = generateKey("platform-key");
    private static final RSAKey UNKNOWN_KEY = generateKey("unknown-key");
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();

    private TestTokens() {
    }

    /** Value of {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}. */
    public static String jwkSetUri() {
        return baseUrl() + "/certs";
    }

    /** Token endpoint for client credentials: always grants a SEAT_MANAGER token to the service account. */
    public static String tokenUri() {
        return baseUrl() + "/token";
    }

    /** Requests received by the token endpoint since the JVM started. */
    public static int tokenRequests() {
        return TOKEN_REQUESTS.get();
    }

    public static String forUser(UUID subject, String... realmRoles) {
        return sign(claims(subject, realmRoles), SIGNING_KEY);
    }

    public static String expired() {
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        return sign(claims(UUID.randomUUID())
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(300))), SIGNING_KEY);
    }

    public static String forAnotherAudience() {
        return sign(claims(UUID.randomUUID()).audience("account"), SIGNING_KEY);
    }

    public static String fromAnotherIssuer() {
        return sign(claims(UUID.randomUUID()).issuer("http://localhost:8180/realms/master"), SIGNING_KEY);
    }

    public static String signedByUnknownKey() {
        return sign(claims(UUID.randomUUID()), UNKNOWN_KEY);
    }

    private static JWTClaimsSet.Builder claims(UUID subject, String... realmRoles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("realm_access", Map.of("roles", List.of(realmRoles)));
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

    private static String baseUrl() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String jwkSet = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
            server.createContext("/certs", exchange -> respond(exchange, jwkSet));
            server.createContext("/token", exchange -> {
                TOKEN_REQUESTS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, "{\"access_token\":\"" + forUser(SERVICE_ACCOUNT, "SEAT_MANAGER")
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
