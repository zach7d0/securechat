-- User Service schema (owned by user-service / role user_svc).
-- Device rows hold identifiers and operational metadata only — never key material.
CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY,           -- Auth Service user id (no cross-database FK)
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    avatar_url TEXT,
    bio TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_profiles_username_prefix ON user_profiles (username text_pattern_ops);
CREATE INDEX idx_profiles_display_lower ON user_profiles (lower(display_name) text_pattern_ops);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_name VARCHAR(100),
    device_type VARCHAR(30),
    platform VARCHAR(40) NOT NULL CHECK (platform IN ('WEB', 'IOS', 'ANDROID', 'DESKTOP')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(20),
    last_authenticated_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ
);
CREATE INDEX idx_devices_user ON devices(user_id);

CREATE TABLE contacts (
    owner_user_id UUID NOT NULL,
    contact_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (owner_user_id, contact_user_id),
    CHECK (owner_user_id <> contact_user_id)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    msg_key VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    last_error TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (next_attempt_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
