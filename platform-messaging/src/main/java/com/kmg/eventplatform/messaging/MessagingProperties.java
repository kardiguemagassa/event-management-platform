package com.kmg.eventplatform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param relayEnabled  publish the outbox in the background; off in tests that drive {@link OutboxRelay#relay()}
 * @param pollInterval  pause between two reads of the outbox when it is empty
 * @param batchSize     messages read per transaction
 * @param sendTimeout   wait for the acknowledgement of Kafka, per message
 * @param retention     published messages and processed message ids older than this are purged
 * @param purgeInterval how often the purge runs
 */
@ConfigurationProperties("platform.messaging")
public record MessagingProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("200ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1h") Duration purgeInterval) {
}
