-- At most one active registration per user and event: the real guarantee under concurrent requests.
-- Partial: cancelled registrations stay as history and the user can register again.
CREATE UNIQUE INDEX uq_registrations_active_event_user
    ON registrations (event_id, user_id)
    WHERE status = 'CONFIRMED';
