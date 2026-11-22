# service-support

Infrastructure wiring shared by services (no business logic). Auto-configured via
`META-INF/spring/…AutoConfiguration.imports`:

| Component | What it does |
|---|---|
| `WebSupportConfiguration` | `X-Correlation-Id` filter (MDC), RFC 9457 problem details (`ApiException`, `ProblemHandler`) |
| `ResourceServerSecurityConfiguration` | JWT (RS256 via Auth JWKS, issuer+audience) for public APIs; `X-Internal-Token` for `/internal/**`; actuator open on the management port only |
| `KafkaSupportConfiguration` | `EventPublisher` (waits for broker ACK); consumer error policy (infinite retry for `RetryableDependencyException`, otherwise 6 retries → `chat.deadletter`) |
| `OutboxConfiguration` | `OutboxWriter` (same-transaction insert), `OutboxPublisher` (`FOR UPDATE SKIP LOCKED`, ACK then mark), `ProcessedEvents` dedupe — enable with `securechat.outbox.enabled=true` |
| `securechat-defaults.yml` | Kafka `acks=all` + idempotence, management port, structured logs, internal service URLs |
| `UuidV7`, `InternalClients` | time-ordered ids; internal RestClient with credential, correlation header and timeouts |

Required tables for services using the outbox (each service owns them in its own Flyway migration):

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    msg_key VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (next_attempt_at) WHERE published_at IS NULL;
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
