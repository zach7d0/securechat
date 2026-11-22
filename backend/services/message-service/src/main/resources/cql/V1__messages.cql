-- Message Service schema (keyspace securechat_message, role message_svc). Ciphertext only.
-- Partition sizing: one partition per (conversation, UTC day). At the MVP limit of ~10k messages
-- per conversation per day × ~2 KiB ≈ 20 MiB worst case; typical partitions are far smaller.

-- History: latest-first per conversation/day; paged backwards with a clustering-key cursor.
CREATE TABLE IF NOT EXISTS messages_by_conversation (
    conversation_id uuid,
    bucket_date date,
    created_at timestamp,
    message_id uuid,
    sender_id uuid,
    sender_device_id uuid,
    ciphertext blob,              -- MLS PrivateMessage, or SIGNAL per-device envelope framing (JSON)
    encryption_protocol text,     -- SIGNAL | MLS
    message_type text,
    client_message_id uuid,
    epoch bigint,
    received_at timestamp,
    membership_version bigint,
    PRIMARY KEY ((conversation_id, bucket_date), created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC, message_id ASC)
  AND default_time_to_live = 31536000;

-- Which day-buckets a conversation has (lets history skip empty days without scanning).
CREATE TABLE IF NOT EXISTS conversation_buckets (
    conversation_id uuid,
    bucket_date date,
    PRIMARY KEY ((conversation_id), bucket_date)
) WITH CLUSTERING ORDER BY (bucket_date DESC)
  AND default_time_to_live = 31622400;

-- Duplicate client submission suppression: (sender device, client message id) → original message.
CREATE TABLE IF NOT EXISTS message_idempotency (
    sender_device_id uuid,
    client_message_id uuid,
    message_id uuid,
    conversation_id uuid,
    created_at timestamp,
    bucket_date date,
    PRIMARY KEY ((sender_device_id, client_message_id))
) WITH default_time_to_live = 604800;

-- Pending outbox events, bucketed by WRITE hour and sharded. Rows are deleted after the Kafka ACK
-- (published marker written first). gc_grace is short: single-replica MVP, no repair dependency.
CREATE TABLE IF NOT EXISTS message_outbox_by_bucket (
    bucket_hour timestamp,
    shard int,
    created_at timestamp,
    event_id uuid,
    message_id uuid,
    conversation_id uuid,
    message_bucket_date date,
    message_created_at timestamp,
    event_type text,
    payload blob,
    status text,                  -- PENDING | STUCK | QUARANTINED
    attempt_count int,
    next_attempt_at timestamp,
    last_error text,
    PRIMARY KEY ((bucket_hour, shard), created_at, event_id)
) WITH CLUSTERING ORDER BY (created_at ASC, event_id ASC)
  AND gc_grace_seconds = 3600;

-- "Published" marker (Kafka acknowledged). Checked before republishing a still-present pending row.
CREATE TABLE IF NOT EXISTS message_outbox_published (
    bucket_hour timestamp,
    shard int,
    event_id uuid,
    published_at timestamp,
    PRIMARY KEY ((bucket_hour, shard), event_id)
) WITH default_time_to_live = 259200;

-- Per-shard publisher lease (LWT) and scan cursor (oldest bucket that may hold pending rows).
CREATE TABLE IF NOT EXISTS outbox_shard_leases (
    shard int PRIMARY KEY,
    owner text,
    lease_until timestamp,
    cursor_bucket timestamp
);
