-- One row per reserved seat. The primary key on reservation_id makes reserve idempotent:
-- replaying the same reservation never takes a second seat.
CREATE TABLE capacity_reservations (
    reservation_id UUID        PRIMARY KEY,
    event_id       UUID        NOT NULL REFERENCES events (id),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_capacity_reservations_event_id ON capacity_reservations (event_id);
