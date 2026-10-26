-- Default listing order is created_at DESC: avoid a full-table sort on every page request
CREATE INDEX idx_users_created_at ON users (created_at DESC);
