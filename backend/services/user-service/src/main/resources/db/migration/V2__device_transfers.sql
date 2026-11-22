-- Device-to-device history transfer (user-approved, end-to-end encrypted).
-- The server stores ONLY: the requesting device's ephemeral HPKE public key + its signature, the
-- ciphertext chunks, and the source device's signed manifest. It can neither decrypt the archive
-- nor redirect it (the ephemeral key is signed by the target device's identity key, verified by the
-- source device, and confirmed by the user comparing a code derived from both identity keys).
CREATE TABLE device_transfers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    target_device_id UUID NOT NULL,         -- new device (requester / receiver)
    source_device_id UUID,                  -- old device that approved
    ephemeral_public_key TEXT NOT NULL,     -- base64 X25519 (HPKE recipient key)
    request_signature TEXT NOT NULL,        -- XEdDSA by the target identity key over the request
    status VARCHAR(12) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'APPROVED', 'READY', 'DECLINED', 'COMPLETED', 'EXPIRED')),
    manifest TEXT,                          -- JSON, signed by the source identity key
    manifest_signature TEXT,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    chunk_count INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_transfers_user ON device_transfers (user_id, status);

CREATE TABLE device_transfer_chunks (
    transfer_id UUID NOT NULL REFERENCES device_transfers(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    data BYTEA NOT NULL,                    -- HPKE ciphertext slice (opaque)
    PRIMARY KEY (transfer_id, seq)
);
