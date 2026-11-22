-- Owner "removed" a device from their list. The row is kept as a tombstone (status REVOKED) so the
-- device id can never be re-registered; it is only hidden from the owner's device list.
ALTER TABLE devices ADD COLUMN removed_at TIMESTAMPTZ;
