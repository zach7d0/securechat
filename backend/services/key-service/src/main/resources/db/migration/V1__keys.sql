-- Key Management Service schema (owned by key-service / role key_svc).
-- PUBLIC key material only. There is intentionally no column that could hold a private key,
-- ratchet state or session secret.
CREATE TABLE identity_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    public_key BYTEA NOT NULL,                 -- Curve25519 identity public key (33 bytes, 0x05 prefix)
    algorithm VARCHAR(80) NOT NULL,
    key_version INTEGER NOT NULL,
    registration_id INTEGER NOT NULL,          -- Signal registration id (public)
    mls_signature_key BYTEA NOT NULL,          -- device's Ed25519 MLS signature public key
    mls_signature_binding BYTEA NOT NULL,      -- XEdDSA signature by the identity key over the binding message
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (device_id, key_version)
);
CREATE INDEX idx_identity_user ON identity_keys(user_id) WHERE revoked_at IS NULL;

CREATE TABLE signed_prekeys (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    key_id BIGINT NOT NULL,
    public_key BYTEA NOT NULL,
    signature BYTEA NOT NULL,
    algorithm VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    UNIQUE (device_id, key_id)
);
CREATE INDEX idx_signed_prekeys_current ON signed_prekeys(device_id, created_at DESC) WHERE retired_at IS NULL;

CREATE TABLE one_time_prekey_inventory (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    key_id BIGINT NOT NULL,
    public_key BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'CLAIMED', 'REVOKED')),
    claimed_at TIMESTAMPTZ,
    claimed_by_device UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (device_id, key_id)
);
CREATE INDEX idx_prekeys_available ON one_time_prekey_inventory(device_id, status);

CREATE TABLE mls_key_packages (
    id UUID PRIMARY KEY,                       -- client-chosen reference (maps to its local private half)
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    key_package BYTEA NOT NULL,                -- public MLSMessage(KeyPackage)
    last_resort BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'CLAIMED', 'REVOKED')),
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_key_packages_available ON mls_key_packages(device_id, status, last_resort);

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
