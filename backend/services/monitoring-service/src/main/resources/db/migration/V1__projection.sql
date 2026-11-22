-- Monitoring projection (owned by monitoring-service / role monitoring_svc).
-- Operational metadata only: ids, timestamps, states, error categories. Never ciphertext/plaintext.
-- Read/reporting data: Message and Delivery services remain authoritative.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE message_lifecycle_events (
    event_id UUID NOT NULL,
    milestone VARCHAR(30) NOT NULL,      -- RECEIVED, PERSISTED, ACCEPTED, PUBLISHED, FANOUT_COMPLETED, DELIVERY_PENDING, DELIVERED, READ, FAILED
    message_id UUID NOT NULL,
    conversation_id UUID,
    service VARCHAR(40) NOT NULL,
    device_id UUID,
    event_time TIMESTAMPTZ NOT NULL,
    error_category VARCHAR(40),
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    PRIMARY KEY (event_id, milestone)
);
CREATE INDEX idx_lifecycle_message ON message_lifecycle_events (message_id, event_time);
CREATE INDEX idx_lifecycle_time ON message_lifecycle_events (event_time);

CREATE TABLE message_delivery_summary (
    message_id UUID PRIMARY KEY,
    conversation_id UUID,
    conversation_type VARCHAR(10),
    protocol VARCHAR(10),
    sender_user_id UUID,
    sender_device_id UUID,
    accepted_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    fanout_at TIMESTAMPTZ,
    expected_devices INT,
    delivered_devices INT NOT NULL DEFAULT 0,
    read_devices INT NOT NULL DEFAULT 0,
    pending_devices INT NOT NULL DEFAULT 0,
    failed_devices INT NOT NULL DEFAULT 0,
    correlation_id VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_summary_conversation ON message_delivery_summary (conversation_id, accepted_at DESC);
CREATE INDEX idx_summary_accepted ON message_delivery_summary (accepted_at DESC);

-- Per eligible recipient device (group / multi-device). Status only moves forward.
CREATE TABLE message_device_delivery (
    message_id UUID NOT NULL,
    device_id UUID NOT NULL,
    user_id UUID,
    status VARCHAR(12) NOT NULL,         -- PENDING, DELIVERED, READ, FAILED
    pending_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_reason VARCHAR(40),
    PRIMARY KEY (message_id, device_id)
);
CREATE INDEX idx_device_delivery_status ON message_device_delivery (status) WHERE status = 'PENDING';

CREATE TABLE message_metrics_hourly (
    bucket_hour TIMESTAMPTZ PRIMARY KEY,
    accepted_count BIGINT NOT NULL DEFAULT 0,
    delivered_device_count BIGINT NOT NULL DEFAULT 0,
    read_device_count BIGINT NOT NULL DEFAULT 0,
    failed_device_count BIGINT NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    latency_count BIGINT NOT NULL DEFAULT 0,
    latency_max_ms BIGINT NOT NULL DEFAULT 0
);

-- required by the shared outbox auto-configuration (unused: this service publishes nothing)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY, topic VARCHAR(200) NOT NULL, msg_key VARCHAR(200) NOT NULL, event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ, last_error TEXT
);
