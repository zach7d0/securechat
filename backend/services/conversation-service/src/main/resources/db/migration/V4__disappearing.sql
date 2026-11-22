-- Disappearing-messages timer per conversation (seconds; NULL/0 = off). Any member may change it; the value is
-- also announced inside the conversation as an encrypted control message. Message ciphertext expires in
-- message-service (Cassandra TTL) and on every device.
ALTER TABLE conversations ADD COLUMN disappearing_seconds INT;
