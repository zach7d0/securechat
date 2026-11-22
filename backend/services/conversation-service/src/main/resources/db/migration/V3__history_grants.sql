-- An OWNER/ADMIN authorises sharing earlier history with newly added members (scope chosen at invite).
-- Any current member device that holds that history may fulfil it: the first to claim the grant
-- (compare-and-set) gets a history_shares row and uploads ciphertext; the key travels in the MLS epoch.
CREATE TABLE history_grants (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    granted_by_user UUID NOT NULL,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('last100', '10m', '1h', '10h', '1d', '7d', '30d', 'all')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_by_user UUID,
    claimed_by_device UUID,
    claimed_at TIMESTAMPTZ,
    share_id UUID REFERENCES history_shares(id) ON DELETE SET NULL
);
CREATE INDEX idx_history_grants_conv ON history_grants(conversation_id);
