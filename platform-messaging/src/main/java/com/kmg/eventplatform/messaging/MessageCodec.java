package com.kmg.eventplatform.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON form of the envelopes, with the application's mapper: payloads are plain records.
 */
public class MessageCodec {

    private final JsonMapper mapper;

    public MessageCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(MessageEnvelope envelope) {
        return mapper.writeValueAsString(envelope);
    }

    /**
     * @throws InvalidMessageException if the value is not an envelope with an id and a type
     */
    public MessageEnvelope decode(String value) {
        MessageEnvelope envelope;
        try {
            envelope = mapper.readValue(value, MessageEnvelope.class);
        } catch (JacksonException notJson) {
            throw new InvalidMessageException("Not a message envelope", notJson);
        }
        if (envelope == null || envelope.messageId() == null || envelope.type() == null) {
            throw new InvalidMessageException("Message envelope without id or type");
        }
        return envelope;
    }

    /**
     * @throws InvalidMessageException if the payload does not match the type
     */
    public <T> T payload(MessageEnvelope envelope, Class<T> type) {
        try {
            return mapper.treeToValue(envelope.payload(), type);
        } catch (JacksonException mismatch) {
            throw new InvalidMessageException(
                    "Payload of message %s is not a %s".formatted(envelope.messageId(), type.getSimpleName()), mismatch);
        }
    }

    JsonNode toTree(Object payload) {
        return mapper.valueToTree(payload);
    }
}
