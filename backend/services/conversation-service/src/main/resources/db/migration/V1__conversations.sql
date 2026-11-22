-- Conversation Service schema (owned by conversation-service / role conversation_svc).
-- No message bodies. MLS commits/welcomes are opaque protocol messages (public or encrypted to
-- recipients); the server only orders and routes them.
CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    conversation_type VARCHAR(20) NOT NULL,
    display_name VARCHAR(150),
    created_by UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    membership_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (conversation_type IN ('DIRECT', 'GROUP'))
);

CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    joined_epoch BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX idx_conversation_members_user ON conversation_members(user_id) WHERE left_at IS NULL;

CREATE TABLE group_metadata (
    conversation_id UUID PRIMARY KEY REFERENCES conversations(id),
    group_description TEXT,
    avatar_url TEXT,
    group_version BIGINT NOT NULL DEFAULT 1,
    mls_group_id VARCHAR(64) NOT NULL UNIQUE,   -- base64 of the MLS GroupID (public)
    epoch BIGINT NOT NULL DEFAULT 0,            -- current MLS epoch (commit compare-and-set)
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Deterministic participant pair → at most one direct conversation per pair, race-free.
CREATE TABLE direct_conversation_pairs (
    user_low UUID NOT NULL,
    user_high UUID NOT NULL,
    conversation_id UUID NOT NULL UNIQUE REFERENCES conversations(id),
    PRIMARY KEY (user_low, user_high),
    CHECK (user_low < user_high)
);

-- Totally ordered MLS commit log: the commit built at epoch N moves the group to N+1.
CREATE TABLE mls_commits (
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    epoch BIGINT NOT NULL,
    commit_message BYTEA NOT NULL,
    sender_user_id UUID NOT NULL,
    sender_device_id UUID NOT NULL,
    kind VARCHAR(20) NOT NULL,                  -- ADD_MEMBERS, REMOVE_MEMBER, DEVICE_SYNC, UPDATE
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, epoch)
);

CREATE TABLE mls_welcomes (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    recipient_user_id UUID NOT NULL,
    recipient_device_id UUID NOT NULL,
    key_package_id UUID,
    welcome BYTEA NOT NULL,
    epoch BIGINT NOT NULL,                      -- epoch the recipient joins at
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_welcomes_device ON mls_welcomes(recipient_device_id) WHERE consumed_at IS NULL;

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
