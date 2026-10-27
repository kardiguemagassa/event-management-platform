package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.TestcontainersConfiguration;
import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventCatalog;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.UserDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
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

    private static final FakeDownstream USER_SERVICE = FakeDownstream.start();
    private static final FakeDownstream EVENT_SERVICE = FakeDownstream.start();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER_SERVICE::url);
        registry.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri", EVENT_SERVICE::url);
    }

    @AfterAll
    static void stopDownstreams() {
        USER_SERVICE.stop();
        EVENT_SERVICE.stop();
    }

    @Autowired
    private UserDirectory users;

    @Autowired
    private EventCatalog events;

    @BeforeEach
    void resetDownstreams() {
        USER_SERVICE.reset();
        EVENT_SERVICE.reset();
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
    void repeatedOutagesOpenTheCircuitWhichThenFailsFast() {
        for (int i = 0; i < 8; i++) {
            USER_SERVICE.answer(503, "");
        }
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> users.exists(UUID.randomUUID()))
                    .isInstanceOf(DownstreamUnavailableException.class);
        }
        assertThat(USER_SERVICE.requests()).isEqualTo(8);

        assertThatThrownBy(() -> users.exists(UUID.randomUUID()))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessageContaining("circuit breaker is open");
        assertThat(USER_SERVICE.requests()).isEqualTo(8);
    }
}
