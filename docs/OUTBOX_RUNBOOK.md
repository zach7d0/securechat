# Outbox Reliability — Design Boundary and Runbook

## Atomicity boundary (Cassandra → Kafka)

Cassandra and Kafka share no transaction, and the message row and the outbox row live in
different tables/partitions, so no single atomic write covers both. Message Service therefore uses
**single-partition writes in a fixed order** (all `LOCAL_QUORUM`):

| Step | Write | If the process dies after this step |
|---|---|---|
| 1 | `message_idempotency` LWT `IF NOT EXISTS` (sender device, clientMessageId) → messageId, createdAt | client got no ACK → retries same id → resolves to the same messageId/createdAt and completes 2–3 |
| 2 | `message_outbox_by_bucket` pending row (bucket = write hour, shard = hash(conversation) mod 8) | outbox row without message: publisher never publishes it; after 2 min it is **QUARANTINED**; the client retry rewrites both rows |
| 3 | `conversation_buckets` + `messages_by_conversation` (ciphertext) | message + pending event exist → will be published |
| 4 | respond **ACCEPTED** | — |

Invariant: **no ACCEPTED message exists without a pending-or-published outbox event.** A
Cassandra failure at any step returns `503 STORE_UNAVAILABLE` (never ACCEPTED).

Publisher (`CassandraOutboxPublisher`, inside message-service):

1. Per shard, acquire/renew an LWT lease (`outbox_shard_leases`, default 30 s; renewed every
   lease/3 during long passes). A crashed instance's shards are taken over after lease expiry.
2. Scan from the shard cursor bucket to the current hour; the cursor only advances over empty
   buckets older than 1 h.
3. Skip rows whose `next_attempt_at` is in the future; later rows of the same conversation wait
   (per-conversation order).
4. If a published marker exists → delete the row (crash-after-marker case; no republish).
5. Verify the message row exists; otherwise quarantine after the grace window.
6. Publish (`acks=all`, idempotent producer) and **wait for the ACK** (10 s timeout).
7. Write the published marker (`message_outbox_published`, TTL 3 d), then delete the pending row.
8. On failure: `attempt_count++`, `next_attempt_at = now + min(2^n s, 5 min) ± 20 %`; after 10
   attempts status `STUCK` (still retried, alerted). While the broker is down the pass stops
   publishing but keeps counting the backlog.

Duplicate windows (consumers must dedupe by `eventId` = UUIDv5(messageId)):
- crash after the Kafka ACK and before the marker → republish;
- a send that timed out may still have been appended by the broker → republish.
Exactly-once delivery is **not** claimed.

## Metrics and alerts

| Metric | Meaning | Alert (see infra/prometheus/alerts.yml) |
|---|---|---|
| `outbox_pending{store}` | pending events in owned shards | > 1000 for 5 min |
| `outbox_oldest_pending_age_seconds{store}` | age of the oldest pending event | > 60 s for 2 min (warning), > 300 s (critical) |
| `outbox_publish_total{result}` | publish successes / failures | failure rate > 0 for 5 min |
| `outbox_quarantined` / `outbox_quarantine_total` | events whose message row is missing | > 0 |
| `outbox_stuck{store="postgres"}` | PG outbox rows past 10 attempts | > 0 |
| `outbox_shards_owned` | shards owned by this instance | sum over instances < 8 for 2 min |

## Recovery commands

Run from the host (internal endpoints are reachable only inside the Docker network):

```bash
source .env
# Per-shard status: owner, lease, cursor, pending, quarantined, oldest pending
docker compose exec -T message-service curl -s -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" \
  localhost:8085/internal/v1/outbox/status | jq .
# After fixing the cause of a quarantine: return QUARANTINED rows to PENDING (they are re-verified
# and re-quarantined if the message row is still missing)
docker compose exec -T message-service curl -s -X POST -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" \
  localhost:8085/internal/v1/outbox/requeue-quarantined
# PostgreSQL outboxes (auth/user/key/conversation): unpublished rows
docker compose exec -T postgres psql -U postgres -d conversation_db -c \
  "SELECT status, count(*), min(created_at) FROM outbox_events WHERE published_at IS NULL GROUP BY status"
# Force immediate retry of STUCK PostgreSQL rows
docker compose exec -T postgres psql -U postgres -d conversation_db -c \
  "UPDATE outbox_events SET next_attempt_at = now() WHERE published_at IS NULL"
# Dead-lettered consumer records
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic chat.deadletter --from-beginning --property print.headers=true --timeout-ms 5000
```

Kafka outage: nothing to do — publication resumes automatically; watch
`outbox_oldest_pending_age_seconds` fall. Publisher crash: another instance (or the restarted
one) takes the shards after the lease expires.
