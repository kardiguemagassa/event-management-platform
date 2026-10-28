CREATE TABLE users (
    id         UUID         PRIMARY KEY,
    full_name  VARCHAR(100) NOT NULL,
    email      VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);
