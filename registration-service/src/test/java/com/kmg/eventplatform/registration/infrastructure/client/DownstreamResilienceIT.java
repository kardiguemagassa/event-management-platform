package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.TestcontainersConfiguration;
import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventCatalog;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.security.test.TestTokens;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real client stack (LoadBalancer, timeouts, retry, circuit breaker) against real HTTP servers.
 * A small breaker window makes the opening observable; read timeout stays at its production value.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "registration.clients.circuit-breaker.sliding-window-size=4",
        "registration.clients.circuit-breaker.minimum-number-of-calls=4",
        "registration.clients.circuit-breaker.wait-duration-in-open-state=1m"})
@Import(TestcontainersConfiguration.class)
class DownstreamResilienceIT {

    private static final FakeDownstream EVENT_SERVICE = FakeDownstream.start();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri", EVENT_SERVICE::url);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", TestTokens::tokenUri);
    }

    @AfterAll
    static void stopDownstreams() {
        EVENT_SERVICE.stop();
    }

    @Autowired
    private EventCatalog events;

    @Autowired
    private Resilience4JCircuitBreakerFactory circuitBreakers;

    /** Each test starts with closed, empty circuits: the outcome of one test never shifts the next one's window. */
    @BeforeEach
    void resetDownstreamAndCircuits() {
        EVENT_SERVICE.reset();
        circuitBreakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    void eventServiceCallsCarryTheServiceAccountToken() {
        int tokenRequestsBefore = TestTokens.tokenRequests();

        events.exists(UUID.randomUUID());
        events.exists(UUID.randomUUID());

        assertThat(EVENT_SERVICE.authorizations()).hasSize(2).allSatisfy(authorization -> {
            assertThat(authorization).startsWith("Bearer ");
            assertThat(payload(authorization)).contains(TestTokens.SERVICE_ACCOUNT.toString(), "SEAT_MANAGER");
        });
        // Cached by the authorized client manager: at most one token request, none if an earlier test got it.
        assertThat(TestTokens.tokenRequests() - tokenRequestsBefore).isLessThanOrEqualTo(1);
    }

    @Test
    void transientFailureIsRetriedOnce() {
        EVENT_SERVICE.answer(503, "");

        assertThat(events.exists(UUID.randomUUID())).isTrue();
        assertThat(EVENT_SERVICE.requests()).isEqualTo(2);
    }

    @Test
    void businessRejectionIsNeverRetried() {
        EVENT_SERVICE.answer(409, "{\"status\":409,\"code\":\"EVENT_FULL\"}");

        assertThatThrownBy(() -> events.reserveSeat(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(EventFullException.class);
        assertThat(EVENT_SERVICE.requests()).isEqualTo(1);
    }

    @Test
    void readTimeoutIsNotRetried() {
        EVENT_SERVICE.answerSlowly(Duration.ofSeconds(3));

        assertThatThrownBy(() -> events.exists(UUID.randomUUID()))
                .isInstanceOf(DownstreamUnavailableException.class);
        assertThat(EVENT_SERVICE.requests()).isEqualTo(1);
    }

    @Test
    void slowAnswerWithinTheReadTimeoutSucceeds() {
        // Would fail if the default 1 s TimeLimiter of Spring Cloud CircuitBreaker were active.
        EVENT_SERVICE.answerSlowly(Duration.ofMillis(1200));

        assertThat(events.exists(UUID.randomUUID())).isTrue();
    }

    @Test
    void organizerIsReadFromTheEvent() {
        UUID organizer = UUID.randomUUID();
        EVENT_SERVICE.answer(200, "{\"id\":\"" + UUID.randomUUID() + "\",\"organizerId\":\"" + organizer + "\"}");

        assertThat(events.organizerOf(UUID.randomUUID())).hasValue(organizer);
    }

    @Test
    void repeatedOutagesOpenTheCircuitWhichThenFailsFast() {
        for (int i = 0; i < 8; i++) {
            EVENT_SERVICE.answer(503, "");
        }
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> events.exists(UUID.randomUUID()))
                    .isInstanceOf(DownstreamUnavailableException.class);
        }
        assertThat(EVENT_SERVICE.requests()).isEqualTo(8);

        assertThatThrownBy(() -> events.exists(UUID.randomUUID()))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessageContaining("circuit breaker is open");
        assertThat(EVENT_SERVICE.requests()).isEqualTo(8);
    }

    /** Claims of a JWT, as text. */
    private static String payload(String authorization) {
        String[] parts = authorization.substring("Bearer ".length()).split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
