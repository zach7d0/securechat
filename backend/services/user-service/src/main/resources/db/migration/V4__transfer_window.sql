-- Automatic device-to-device history sync: a request names the time window it wants (at most ~one month).
-- The window is also bound into the request signature by the client, so the server cannot widen it.
ALTER TABLE device_transfers ADD COLUMN window_from TIMESTAMPTZ;
ALTER TABLE device_transfers ADD COLUMN window_to TIMESTAMPTZ;
CREATE INDEX idx_transfers_user_created ON device_transfers (user_id, created_at);
