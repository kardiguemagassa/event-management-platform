package com.kmg.eventplatform.registration.infrastructure.messaging;

import com.kmg.eventplatform.messaging.MessageCodec;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter: reads the envelope, the handler does the rest. A failure is retried, then the reply goes to the
 * dead letter topic (platform-messaging); an unreadable one goes there at once.
 */
@Component
public class SeatReplyListener {

    private final MessageCodec codec;
    private final SeatReplyHandler handler;

    public SeatReplyListener(MessageCodec codec, SeatReplyHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @KafkaListener(topics = SeatMessages.REPLIES_TOPIC, groupId = "${spring.application.name}")
    public void on(String value) {
        handler.handle(codec.decode(value));
    }
}
