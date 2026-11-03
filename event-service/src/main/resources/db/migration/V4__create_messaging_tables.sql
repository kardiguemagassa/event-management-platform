-- Outbox and processed messages of platform-messaging (reference: com/kmg/eventplatform/messaging/schema-postgresql.sql).
-- event-service writes the seat replies to the outbox and records the seat commands it has handled.

-- Messages written in the transaction of a business change, published to Kafka afterwards by the relay.
CREATE TABLE outbox_messages (
    -- Publication order: the relay reads by sequence, so the messages of a key reach Kafka in the order written.
    sequence     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id   UUID         NOT NULL UNIQUE,
    topic        VARCHAR(255) NOT NULL,
    message_key  VARCHAR(255) NOT NULL,
    -- The envelope as sent: {messageId, type, occurredAt, payload}.
    payload      TEXT         NOT NULL,
    -- W3C trace context of the request that wrote the message, restored when it is published.
    traceparent  VARCHAR(55),
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- What the relay polls: small, since published rows leave it.
CREATE INDEX idx_outbox_messages_unpublished ON outbox_messages (sequence) WHERE published_at IS NULL;
-- Purge of the published rows after the retention period.
CREATE INDEX idx_outbox_messages_published_at ON outbox_messages (published_at) WHERE published_at IS NOT NULL;

-- Messages already handled by a consumer, written in the transaction of their effect: a redelivery is a no-op.
CREATE TABLE processed_messages (
    message_id   UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_processed_messages_processed_at ON processed_messages (processed_at);
