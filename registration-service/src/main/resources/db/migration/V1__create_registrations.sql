-- event_id and user_id reference other services by identity only: no foreign key across databases.
CREATE TABLE registrations (
    id           UUID        PRIMARY KEY,
    event_id     UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_registrations_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_registrations_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- GET /api/registrations/events/{eventId} and /users/{userId}: filter + sort by date
CREATE INDEX idx_registrations_event_id ON registrations (event_id, created_at);
CREATE INDEX idx_registrations_user_id ON registrations (user_id, created_at);
