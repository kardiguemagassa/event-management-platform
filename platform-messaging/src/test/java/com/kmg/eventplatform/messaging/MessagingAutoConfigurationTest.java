package com.kmg.eventplatform.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingAutoConfiguration.class))
            .withPropertyValues("platform.messaging.relay-enabled=false")
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ProducerFactory.class, () -> mock(ProducerFactory.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    void servicesGetTheOutboxTheRelayAndIdempotentConsumption() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Outbox.class).hasSingleBean(OutboxRelay.class)
                    .hasSingleBean(ProcessedMessages.class).hasSingleBean(MessageCodec.class)
                    .hasSingleBean(DefaultErrorHandler.class).hasSingleBean(OutboxMetrics.class);
            assertThat(context.getBean(OutboxRelay.class).isAutoStartup()).isFalse();
            assertThat(context.getBean(OutboxRelay.class).isRunning()).isFalse();
        });
    }

    @Test
    void anErrorHandlerOfTheServiceWins() {
        runner.withBean("custom", CommonErrorHandler.class, () -> mock(CommonErrorHandler.class)).run(context ->
                assertThat(context).doesNotHaveBean(DefaultErrorHandler.class).hasSingleBean(CommonErrorHandler.class));
    }

    @Test
    void defaultsPublishInTheBackgroundAndKeepAWeek() {
        MessagingProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("platform.messaging", MessagingProperties.class);

        assertThat(defaults.relayEnabled()).isTrue();
        assertThat(defaults.pollInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(defaults.batchSize()).isEqualTo(100);
        assertThat(defaults.sendTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.purgeInterval()).isEqualTo(Duration.ofHours(1));
    }
}
