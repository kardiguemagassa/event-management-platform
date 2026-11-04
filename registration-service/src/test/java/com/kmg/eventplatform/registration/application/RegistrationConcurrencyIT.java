package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.KafkaTestcontainersConfiguration;
import com.kmg.eventplatform.registration.TestcontainersConfiguration;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full context against a real PostgreSQL: the unique index, not the pre-check, must win the race, and the losers
 * write no seat request (their transaction, outbox included, is rolled back).
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class RegistrationConcurrencyIT {

    private static final int ATTEMPTS = 10;

    @Autowired
    private RegistrationService service;

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private DataSource dataSource;

    @Test
    void concurrentDuplicatesYieldOneRegistrationAndOneSeatRequest() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Registration>> attempts = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
            for (int i = 0; i < ATTEMPTS; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return service.register(eventId, userId);
                }));
            }
            start.countDown();
        }

        int accepted = 0;
        for (Future<Registration> attempt : attempts) {
            try {
                attempt.get();
                accepted++;
            } catch (ExecutionException rejected) {
                assertThat(rejected.getCause()).isInstanceOf(DuplicateRegistrationException.class);
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(repository.findByEventId(eventId, PageRequest.of(0, ATTEMPTS)).getTotalElements()).isEqualTo(1);
        assertThat(JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM outbox_messages WHERE message_key = :key")
                .param("key", eventId.toString())
                .query(Long.class).single())
                .as("seat requests written").isEqualTo(1);
    }
}
