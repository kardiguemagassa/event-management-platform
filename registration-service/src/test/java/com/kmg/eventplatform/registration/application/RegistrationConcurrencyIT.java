package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.TestcontainersConfiguration;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Full context against a real PostgreSQL: the unique index, not the pre-check, must win the race.
 * Downstream services are replaced by thread-safe stubs that track held seats.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@Import(TestcontainersConfiguration.class)
class RegistrationConcurrencyIT {

    private static final int ATTEMPTS = 10;

    @Autowired
    private RegistrationService service;

    @Autowired
    private RegistrationRepository repository;

    @MockitoBean
    private EventCatalog events;

    private final Set<UUID> heldSeats = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void stubDownstreams() {
        when(events.exists(any())).thenReturn(true);
        doAnswer(invocation -> heldSeats.add(invocation.getArgument(1))).when(events).reserveSeat(any(), any());
        doAnswer(invocation -> heldSeats.remove(invocation.getArgument(1))).when(events).releaseSeat(any(), any());
    }

    @Test
    void concurrentDuplicatesYieldOneRegistrationAndOneHeldSeat() throws Exception {
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

        int confirmed = 0;
        for (Future<Registration> attempt : attempts) {
            try {
                attempt.get();
                confirmed++;
            } catch (ExecutionException rejected) {
                assertThat(rejected.getCause()).isInstanceOf(DuplicateRegistrationException.class);
            }
        }
        assertThat(confirmed).isEqualTo(1);
        assertThat(repository.findByEventId(eventId, PageRequest.of(0, ATTEMPTS)).getTotalElements()).isEqualTo(1);
        assertThat(heldSeats).hasSize(1);
    }
}
