-- Delivery Service schema (keyspace securechat_delivery, role delivery_svc). Ciphertext only.

-- Durable per-device mailbox, one partition per (device, UTC day) → bounded partitions.
CREATE TABLE IF NOT EXISTS pending_messages_by_device (
    recipient_device_id uuid,
    bucket_date date,
    created_at timestamp,
    message_id uuid,
    delivery_id uuid,
    conversation_id uuid,
    sender_user_id uuid,
    sender_device_id uuid,
    recipient_user_id uuid,
    ciphertext blob,
    protocol text,
    message_type text,
    signal_type int,
    epoch bigint,
    attempt_count int,
    last_attempt_at timestamp,
    enqueued_at timestamp,
    PRIMARY KEY ((recipient_device_id, bucket_date), created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at ASC, message_id ASC)
  AND default_time_to_live = 1296000
  AND gc_grace_seconds = 3600;

-- Which day-buckets of a device may still hold pending rows.
CREATE TABLE IF NOT EXISTS pending_buckets_by_device (
    recipient_device_id uuid,
    bucket_date date,
    PRIMARY KEY ((recipient_device_id), bucket_date)
) WITH CLUSTERING ORDER BY (bucket_date ASC)
  AND default_time_to_live = 1296000
  AND gc_grace_seconds = 3600;

-- Per-device receipt state for one message (forward-only transitions via LWT).
CREATE TABLE IF NOT EXISTS message_receipts_by_message (
    message_id uuid,
    recipient_device_id uuid,
    recipient_user_id uuid,
    sender_user_id uuid,
    conversation_id uuid,
    status text,                 -- PENDING | DELIVERED | READ | FAILED
    failure_reason text,
    message_created_at timestamp,
    bucket_date date,
    updated_at timestamp,
    delivered_at timestamp,
    read_at timestamp,
    PRIMARY KEY ((message_id), recipient_device_id)
) WITH default_time_to_live = 31536000;

-- Work index for the retry/expiry sweeper: devices that still have pending rows.
CREATE TABLE IF NOT EXISTS devices_with_pending (
    shard int,
    recipient_device_id uuid,
    recipient_user_id uuid,
    PRIMARY KEY ((shard), recipient_device_id)
) WITH gc_grace_seconds = 3600;

CREATE TABLE IF NOT EXISTS sweeper_leases (
    shard int PRIMARY KEY,
    owner text,
    lease_until timestamp
);
