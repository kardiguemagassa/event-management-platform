-- Keycloak id (sub) of the organizer: only they may change the event and see it as a draft.
-- Nullable: events created before ownership have no organizer and can no longer be changed.
ALTER TABLE events ADD COLUMN organizer_id UUID;

-- "My drafts": drafts of one organizer, sorted by date.
CREATE INDEX idx_events_organizer_id ON events (organizer_id, start_at);
