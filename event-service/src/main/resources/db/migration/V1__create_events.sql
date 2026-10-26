CREATE TABLE events (
    id             UUID          PRIMARY KEY,
    title          VARCHAR(200)  NOT NULL,
    description    VARCHAR(2000),
    location       VARCHAR(200)  NOT NULL,
    start_at       TIMESTAMPTZ   NOT NULL,
    capacity       INTEGER       NOT NULL,
    reserved_seats INTEGER       NOT NULL DEFAULT 0,
    status         VARCHAR(20)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_events_capacity_positive CHECK (capacity > 0),
    CONSTRAINT ck_events_reserved_seats_range CHECK (reserved_seats BETWEEN 0 AND capacity),
    CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED'))
);

-- "Published events by date": filter and sort served by a single index
CREATE INDEX idx_events_status_start_at ON events (status, start_at);
-- Default listing order when no status filter is given
CREATE INDEX idx_events_start_at ON events (start_at);
