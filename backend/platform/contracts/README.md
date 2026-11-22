# SecureChat Contracts (v1)

Shared, versioned contracts. **No business logic.** Producers and consumers depend on this module
so both sides use the same envelope, topic names, payload records and JSON Schemas.

## Kafka events

- Envelope: `EventEnvelope` — `eventId, eventType, eventVersion, correlationId, traceId,
  timestamp, aggregateId, producer, payload`. Schema: `schemas/envelope.v1.json`.
- Registry: `EventType` (wire name, version, topic, payload record). Schemas:
  `src/main/resources/schemas/<wireName>.v<version>.json` (JSON Schema 2020-12).
- Serialisation: `EventCodec` (Jackson 3; unknown fields ignored; ISO-8601 timestamps).
- Deterministic IDs: `DeterministicIds` (UUIDv5) — `deliveryId(messageId, deviceId)`,
  `acceptedEventId(messageId)` etc., so republished events keep their identity.

### Compatibility rules

1. Within a major `eventVersion`, producers may only **add optional fields**.
2. Consumers ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES` disabled) and must tolerate
   duplicates (dedupe by `eventId` or by idempotent state transition).
3. A breaking change (remove/rename/retype/make required) requires `v(N+1)`: a new schema file, a
   new payload record, and parallel publication until every consumer has migrated.
4. Published schema files are immutable — `ContractTest.publishedSchemasAreImmutable` compares
   them with `schemas/CHECKSUMS`. Adding a version: `mvn -pl platform/contracts test -Dcontracts.updateLock=true`.

Contract tests (`mvn -pl platform/contracts test`): every producer sample validates against its
schema; the envelope validates; unknown fields are tolerated; record fields and schema properties are
identical; published schemas are unchanged; invalid payloads are rejected.

## REST error contract

All services return RFC 9457 problem details (`application/problem+json`):

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "code": "EPOCH_CONFLICT",
  "detail": "group epoch is 7", "correlationId": "…" }
```

Stable `code` values: `VALIDATION_FAILED`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`,
`CONFLICT`, `RATE_LIMITED`, `INVALID_CREDENTIALS`, `TOKEN_REUSED`, `DEVICE_REQUIRED`,
`DEVICE_REVOKED`, `ACCOUNT_SUSPENDED`, `NOT_A_MEMBER`, `EPOCH_CONFLICT`, `EPOCH_MISMATCH`,
`DEVICE_LIST_STALE`, `IDEMPOTENCY_CONFLICT`, `STORE_UNAVAILABLE`, `DEPENDENCY_UNAVAILABLE`,
`INTERNAL_ERROR`.

Headers: `X-Correlation-Id` (accepted and echoed; generated if absent), W3C `traceparent`.

## WebSocket protocol (`/ws`, JSON text frames)

The connection is unauthenticated until the first frame, which must be `auth` within 5 s
(close `4401` otherwise). Tokens are never put in the URL.

### Client → server

| type | fields | notes |
|---|---|---|
| `auth` | `token` | device-bound access token (`did` claim). Re-send before expiry to refresh. |
| `send` | `clientMessageId, conversationId, protocol (SIGNAL\|MLS), messageType, envelopes? [{deviceId, signalType, ciphertext}], ciphertext?, epoch?` | ciphertext only |
| `ack` | `status (DELIVERED\|READ), messageIds[]` | per-device receipts |
| `typing` | `conversationId, state (start\|stop)` | ephemeral |
| `sync` | — | replay pending deliveries for this device |
| `ping` | — | heartbeat (every 20 s) |

### Server → client

| type | fields |
|---|---|
| `ready` | `userId, deviceId, gatewayId, tokenExpiresAt` |
| `accepted` | `clientMessageId, messageId, createdAt, status: "ACCEPTED"` |
| `error` | `clientMessageId?, code, message, details?` |
| `message` | `deliveryId, messageId, conversationId, senderUserId, senderDeviceId, protocol, messageType, signalType?, epoch?, ciphertext, createdAt` |
| `receipt` | `messageId, conversationId, status, userId, deviceId, at` |
| `presence` | `userId, online, lastSeen?` |
| `typing` | `conversationId, userId, state` |
| `membership` | `conversationId, event (created\|member.added\|member.removed\|commit), epoch?, membershipVersion?` |
| `prekeys_low` | `remainingPrekeys, remainingKeyPackages` |
| `revoked` | `reason` — followed by close `4403` |
| `pong` | — |

Close codes: `4401` unauthenticated/expired, `4403` device revoked/suspended, `4409` replaced by a
newer connection of the same device, `4429` rate limited, `1012` gateway restarting (reconnect).
