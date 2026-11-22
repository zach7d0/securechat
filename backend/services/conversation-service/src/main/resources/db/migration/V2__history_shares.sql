-- Group history shared with newly added members. The admin's device encrypts its local history with a
-- fresh random key K (AES-256-GCM) and uploads only ciphertext here; K travels inside an MLS application
-- message of the new epoch, so the server can never decrypt these chunks.
CREATE TABLE history_shares (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    created_by_user UUID NOT NULL,
    created_by_device UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING' CHECK (status IN ('UPLOADING', 'READY')),
    chunk_count INT,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_history_shares_conv ON history_shares(conversation_id);
CREATE INDEX idx_history_shares_expiry ON history_shares(expires_at);

CREATE TABLE history_share_chunks (
    share_id UUID NOT NULL REFERENCES history_shares(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    data BYTEA NOT NULL,
    PRIMARY KEY (share_id, seq)
);
