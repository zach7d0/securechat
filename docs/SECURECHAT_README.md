# SecureChat

**Project overview, requirements and MVP scope**
Version: 1.0

## 1. Product scope and delivery roadmap

SecureChat is delivered in clearly separated stages. The current implementation
target is the **MVP: Chat System + Admin Portal**. The feature roadmap (§8) lists what comes next; production
deployment guidance (§10–11) is a set of recommendations for operators, not a product phase.

| Scope            | Included capabilities                                                                                                                                                         | Delivery status |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| Chat System      | Registration, login, user profile, devices, one-to-one and group chat, E2EE, WebSocket messaging, presence, offline delivery, sent/delivered/read states, edit (1 h) / delete for everyone (24 h) / delete for me, disappearing messages                      | **CURRENT MVP** |
| Admin Portal     | Admin login/RBAC, registered-user listing and search, user/device status, message lifecycle investigation, service-health overview, basic reports, alerts and admin audit log | **CURRENT MVP** |
| Near-term features | "Keep messages for…" auto-clear, reply/reactions, push notifications, encrypted backup and account recovery, privacy settings and blocking, group invite links and announcement groups, QR device linking and verification (no new infrastructure) | **NEAR-TERM** |
| Future improvements | Photos and files, voice messages, encrypted voice/video calls, installable/mobile apps, on-device search, link previews (need file storage, bandwidth or new platforms) | **FUTURE** |
| Production deployment | High availability, managed data stores, scaling, observability, backup and disaster recovery | **RECOMMENDATIONS** (§10–11) |

### Scope rules

- The MVP is a working chat product plus a separate, protected Admin Portal.
- A registered user can use the chat application. Authorized administrators
  can view registered-user records and operational metadata through the Admin
  Portal.
- The Admin Portal must not expose message plaintext or E2EE private keys.
- The MVP may include basic metrics, health checks, message lifecycle tracking
  and operational reports. It does not require a fully distributed,
  multi-region production platform.
- New features follow the roadmap in §8 (near-term focus first). Multi-AZ, Kubernetes and multi-region
  infrastructure are deployment recommendations (§11), not product features.

## 2. Project overview

SecureChat is an end-to-end encrypted (E2EE) messaging platform built
with React and Java Spring Boot microservices. It supports one-to-one
conversations, group conversations, real-time messaging, online
presence, multi-device delivery, and offline message synchronization.

The system is designed to run locally through Docker Compose and to be
deployable as a single-EC2 MVP. Its service boundaries and data
architecture should allow a later move to highly available, horizontally
scaled infrastructure.

### Core security principle

Message plaintext and private encryption keys remain on client devices.
Backend services route and persist ciphertext and necessary metadata;
they must not decrypt user messages.

Use maintained, audited cryptographic protocol implementations. Do not
invent cryptographic algorithms or claim E2EE until the selected client
library, key lifecycle, device model, group protocol, and security tests
are working.

## 3. Product goals

- Provide private one-to-one messaging.
- Provide E2EE group messaging.
- Deliver messages in real time through WebSockets.
- Show user and device online/offline status.
- Support multiple devices per user.
- Deliver messages to offline recipients after reconnection.
- Track sent, delivered, and read states.
- Support group member addition/removal and corresponding
  cryptographic key changes.
- Provide reliable message persistence and event publication.
- Run locally and on a single EC2 instance.

## 4. Technology stack

| Layer             | Technology                                 | Responsibility                                                 |
| ----------------- | ------------------------------------------ | -------------------------------------------------------------- |
| Web client        | React, TypeScript, Vite                    | Chat UI and client-side cryptography                           |
| Client storage    | IndexedDB                                  | Protected local key/session state and local queue              |
| Backend           | Java 21+, Spring Boot 3+                   | Independent microservices                                      |
| REST gateway      | Spring Cloud Gateway                       | API routing, authentication enforcement and rate limits        |
| WebSocket gateway | Spring WebSocket                           | Persistent client connections and real-time transport          |
| Relational data   | PostgreSQL                                 | Accounts, devices, conversations, memberships and key metadata |
| Message data      | Apache Cassandra                           | Encrypted message history and high-volume delivery records     |
| Ephemeral state   | Redis                                      | Presence, heartbeats, connection registry and cache            |
| Event backbone    | Apache Kafka (KRaft)                       | Asynchronous service events                                    |
| Local runtime     | Docker Compose                             | Repeatable local environment                                   |
| Cloud MVP         | AWS EC2 + EBS + Nginx                      | Single-host deployment                                         |
| Observability     | OpenTelemetry, Prometheus, Grafana, Jaeger | Metrics, traces and dashboards                                 |

## 5. MVP functional requirements

The MVP has two user-facing applications/surfaces:

1. **Chat Application** for registered end users.
2. **Admin Portal** for authorized administrators and operations staff.

### Admin Portal (CURRENT MVP)

- Separate React admin application or clearly isolated admin route/build.
- Admin authentication and role-based access control (RBAC).
- Dashboard showing registered users, active/online users, message volume,
  delivery outcomes and service health.
- Registered-user list with search, pagination and filters; display profile
  and account status without exposing credentials or secrets.
- User/device operational view, including account status and registered
  device metadata.
- Message trace lookup by message ID, conversation ID and time range.
- Lifecycle milestones: received, persisted, published, fanout completed,
  delivery pending, delivered, read and failed.
- Per-device delivery status for group and multi-device messages.
- Basic aggregate reports: accepted, delivered, pending and failed counts,
  delivery latency and failure categories — for the last 24 h, 7 or 30 days, or
  a chosen date/time range (≤ 92 days).
- User accounts: suspend / reactivate; device revocation.
- Administrator accounts: create (choose roles and whether MFA is required),
  enable/disable, lock/unlock (also clears failed-login lockouts), delete; at
  least one active, unlocked SUPER_ADMIN always remains.
- Basic service health and alert visibility using the observability stack.
- Admin audit trail for sensitive actions and access to operational records.
- No plaintext message viewer, private-key viewer or decryption capability.

### Authentication and users

- Register, log in, refresh token, log out.
- Passwords stored using a strong password hashing algorithm such as
  Argon2id.
- User profile and username search.
- Profile: view own email (private to the owner) and change it; change
  password (current password required; other devices are signed out).
- Devices: registration; **Revoke** (sign a device out for good) and
  **Remove** (revoke and take off the list; its id can never be reused).
- JWT validation for REST and WebSocket connections.

### One-to-one chat

- Create or retrieve a direct conversation.
- Establish an E2EE session between devices.
- Send and receive encrypted messages.
- Retrieve message history: **Load older messages** loads one month per click.
- A new device of the same user receives the last month of history
  automatically from the user's other devices (end-to-end encrypted relay, no
  manual approval; the sending device shows "sent to new device — Not you?
  Revoke"). Older months are fetched the same way when Load older reaches them.
- Show sent, delivered, and read indicators.
- Support offline recipient delivery.
- Emoji picker in the composer (categories, search, recently used).
- Chat theme: System / Light / Dark appearance, six colour themes (wallpaper,
  outgoing bubble, accent) and four wallpaper patterns; saved per device only.

### Welcome tour (onboarding)

- A step-by-step tour highlights the real controls: start a one-to-one chat (search a username, e.g. "alice";
  your friend needs an account first, or ask them to start the chat), create a group (you become the owner),
  the chat list, in-chat features (emoji, edit/delete, disappearing messages, safety numbers, group info) and
  settings. The last step offers **Start a chat** / **Create a group**.
- Starts by itself right after sign-up, or on the first login on a device when the user has no chats yet;
  never again once finished or skipped.
- **Settings → Help & tour**: "Start the tour again", a "Show the tour automatically" switch (per device), and
  short how-tos.
- Operator switch: `ONBOARDING_ENABLED` (`.env`, default `true`). The frontend container serves it as
  `/config.json` at start-up, so changing it needs `docker compose up -d frontend`, not a rebuild. When off,
  the tour never starts and the menu entry is hidden.

### Edit, delete and disappearing messages (one-to-one and groups)

| Action | Who | Time limit | Result |
|---|---|---|---|
| Edit | the sender | **1 hour** after sending | new text on every device, marked "edited" |
| Delete for everyone | the sender only (no admin override) | **24 hours** after sending | "This message was deleted" on every device; removed from server history |
| Delete for me | anyone, any message | none | removed from this device only |
| Disappearing-messages timer | **any member** (groups too) | — | Off / 5 min / 15 min / 1 h / 3 h / 10 h / 1 day / 3 days / 7 days / 30 days |

- Time limits use the **server's** send time, not the sender's clock.
- Edits and deletes are encrypted control messages; devices ignore one that is not from the original
  sender or arrives outside the window.
- Disappearing timer: counted **from when each message was sent**. Changing it is announced to everyone
  ("Bob set disappearing messages to 1 hour") and the chat header shows the current timer; members and
  devices that join later get the current setting. Expired messages are removed from every device and the
  server copy expires automatically (Cassandra TTL). Messages sent before the timer was turned on are kept.
- Open the menu by hovering a message and clicking ▾, right-clicking, or long-pressing on touch screens.

### Group chat

- Create a group and manage members.
- Support group encryption using a maintained protocol implementation.
- Handle member additions and removals.
- Re-key or update group cryptographic state as required by the
  selected protocol.
- Prevent removed members from decrypting messages sent after their
  removal, subject to protocol guarantees.
- Any member can rotate the group keys manually (MLS update commit).
- Roles: OWNER (creator, exactly one), ADMIN, MEMBER.

| Action | OWNER | ADMIN | MEMBER |
|---|---|---|---|
| Invite members | ✅ as Admin or Member | ✅ as Member only | ❌ |
| Choose earlier history the invitee can see | ✅ | ✅ | ❌ |
| Remove a member / remove an admin | ✅ / ✅ | ✅ / ❌ | ❌ |
| Promote / demote admins | ✅ | ❌ | ❌ |
| Edit group name and description | ✅ | ✅ | ❌ |
| Rotate group keys, leave | ✅ | ✅ | ✅ |

- History for new members, chosen at invite: Don't share, last 100 messages,
  last 10 min / 1 h / 10 h / 1 day / 7 days / 30 days, or all. A member device
  that holds that history re-encrypts it for the group (the admin's device first,
  otherwise any member's); everyone is told. Without it the new member sees
  "You can't see messages from before you joined".

### Real-time and presence

- WebSocket connection and reconnection.
- Device-level heartbeats.
- User online/offline status derived from active device connections.
- Last-seen metadata.
- Typing indicators (optional MVP feature).
- Cross-WebSocket-gateway routing design.

### Reliability

- Durable message persistence before accepted acknowledgement.
- Reliable Cassandra-to-Kafka event publication using an explicitly
  designed outbox or CDC mechanism.
- Idempotent consumers and delivery processing.
- Retry and dead-letter handling.
- Offline delivery queue and reconnect synchronization.
- Failure tests for Cassandra, Kafka, Redis, PostgreSQL, gateways, and
  consumers.

## 6. Non-functional requirements

- **Security:** E2EE, TLS, strong authentication, authorization
  checks, secret management, rate limiting, input validation.
- **Reliability:** No acknowledged durable message should be silently
  lost; retry and recovery paths must be observable.
- **Consistency:** Define message acceptance, publication, delivery,
  and read semantics separately.
- **Scalability:** Stateless application services where practical;
  partition Kafka events and Cassandra tables by access patterns.
- **Observability:** Structured logs, correlation IDs, distributed
  traces, metrics, health and readiness endpoints.
- **Maintainability:** Independent service ownership, versioned
  API/event contracts, automated tests, documented runbooks.
- **Portability:** Local Docker Compose and EC2 deployment use the
  same container images and configuration model.

## 7. MVP microservices

| Service                         | Primary responsibility                                                                                         | MVP data store                                                                                  |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| API Gateway                     | REST routing, JWT enforcement, rate limiting                                                                   | None; stateless                                                                                 |
| WebSocket Gateway               | WebSocket connections, message ingress/egress, gateway routing                                                 | Redis for connection registry; no durable message ownership                                     |
| Auth Service                    | Credentials, sessions, refresh tokens                                                                          | PostgreSQL                                                                                      |
| User Service                    | Profiles, devices, contacts                                                                                    | PostgreSQL                                                                                      |
| Key Management Service          | Public identity keys, signed prekeys, one-time prekey inventory and key metadata                               | PostgreSQL; private keys remain client-side                                                     |
| Conversation Service            | Conversations, memberships, group metadata                                                                     | PostgreSQL                                                                                      |
| Message Service                 | Ciphertext persistence, message IDs, transactional/outbox event creation                                       | Cassandra                                                                                       |
| Fanout Service                  | Recipient/device resolution and routing to connected gateways                                                  | Redis cache plus Conversation/User APIs and Kafka events                                        |
| Presence Service                | Online/offline state, heartbeats, last-seen calculation                                                        | Redis; ephemeral and rebuildable                                                                |
| Delivery Service                | Per-device pending delivery, acknowledgements, receipts and retries                                            | Cassandra                                                                                       |
| Notification Service            | Push notification orchestration and provider retries                                                           | No dedicated database initially; PostgreSQL only if durable preferences or jobs are required    |
| Admin API / Admin Service       | Admin RBAC, user/operational read APIs, report aggregation and audit actions; never bypasses service ownership | PostgreSQL for admin roles/audit metadata; read through service APIs and monitoring projections |
| Monitoring Consumer / Reporting | Consume lifecycle events and build searchable operational projections and aggregate reports                    | PostgreSQL initially; analytical store only when justified by measured volume                   |

Each service owns its data. Other services must not query its database
directly. Use APIs or events. For the MVP, several services may share a
PostgreSQL or Cassandra cluster, but use separate schemas/keyspaces,
credentials and ownership boundaries. Do not interpret shared
infrastructure as shared data ownership.

## 8. Feature roadmap

The roadmap is about **features** and matches the project README. Most items are new kinds of encrypted messages or
client settings, so the server keeps handling ciphertext only.

### Near-term focus (no new infrastructure)

- "Keep messages for…" (forever / 1 year / 6 months / 30 days): auto-clear old history on the device.
- Reply and reactions.
- Push notifications: content-free "new message" alerts (browser Web Push).
- Encrypted backup (passphrase-protected) and account recovery (password reset).
- Privacy settings and blocking: hide last seen / read receipts, control who can add you to groups, block or report users.
- Group invite links (with admin approval) and announcement groups ("only admins can post").
- Link a new device by QR code; verify contacts by QR code.

### Future improvements (need additional infrastructure, running cost or new platforms)

- Photos and files: encrypted on the device with a fresh key, only ciphertext stored in object storage (S3/MinIO), key sent inside the encrypted message.
- Voice messages (encrypted like files).
- End-to-end encrypted voice and video calls (WebRTC with a media relay).
- Installable app (PWA) and mobile apps with offline mode.
- Message search on the device (the server cannot search, by design).
- Link previews generated by the sender's device.

Production-scale operation (multi-AZ, autoscaling, managed stores, disaster recovery) is covered as
**recommendations** in §10–11, not as a roadmap phase.

## 9. Not planned

- Federation with other chat networks.
- Automated content moderation.
- Guaranteed metadata privacy.
- Multi-tenant (SaaS) administration.

Voice/video calls and file/media storage are on the roadmap as future improvements (§8).

## 10. Deployment targets

### Local development

Run React, all Spring Boot services, PostgreSQL, Cassandra, Redis,
Kafka, and optional monitoring tools using Docker Compose. Local
infrastructure may use one Cassandra node and one Kafka broker; this is
for development and integration testing, not HA.

### Single-EC2 MVP

Deploy containers on one EC2 instance with persistent EBS storage,
Nginx, TLS, private container networking, backups, and health checks.
Expose only HTTP/HTTPS publicly. This topology is a single point of
failure and must not be described as highly available.

### Production deployment (recommended before serving real users)

The following are recommendations for operators running SecureChat for real users; they are not part of the MVP acceptance criteria.

The single-EC2 deployment is a development/MVP topology, not an HA
production design. Evolve in stages:

1. **Separate stateful and stateless tiers:** move PostgreSQL, message
   storage, Redis and Kafka away from the application host. Run at least
   two stateless API/worker instances.
2. **Multi-AZ availability:** deploy app replicas across AZs; use
   PostgreSQL Multi-AZ, replicated Kafka and multi-node Cassandra (if
   selected), plus Redis failover.
3. **Independent horizontal scaling:** scale WebSocket Gateway,
   Message, Fanout, Delivery and Notification workers separately based
   on concurrent connections, messages/sec, Kafka lag and pending
   delivery backlog.
4. **Disaster recovery:** maintain encrypted off-host backups, PITR or
   database snapshots, cross-region copies where required, IaC-based
   rebuilds and tested recovery runbooks.
5. **Regional scale:** start with active-passive regional recovery.
   Consider active-active only after defining message ordering,
   conflict resolution, data residency and cross-region consistency.

Recommended production data placement:

| Data                                                      | Recommended production store                                                                                                      |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Accounts, credentials, devices                            | RDS/Aurora PostgreSQL Multi-AZ                                                                                                    |
| Conversations, memberships, public key metadata           | PostgreSQL HA                                                                                                                     |
| Encrypted message history and per-device pending delivery | Cassandra multi-AZ, DynamoDB, ScyllaDB or managed Cassandra-compatible service; choose from measured workload and operating model |
| Presence and live connection registry                     | Redis replication/failover; rebuildable                                                                                           |
| Event streaming                                           | Multi-AZ Kafka or managed Kafka                                                                                                   |
| Backups and archives                                      | Encrypted S3 with versioning, lifecycle and optional cross-region replication                                                     |

Do not migrate away from PostgreSQL simply because a table has millions
of rows. First fix query patterns, indexes, partitioning, connection
pooling and vacuum health. Add a distributed message store when measured
throughput, retention, independent scaling or availability requirements
justify it.

## 11. Production recommendations: scaling, resilience and recovery

### Scale the right services independently

| Component                     | Scale when                              | Production design                                                   |
| ----------------------------- | --------------------------------------- | ------------------------------------------------------------------- |
| API Gateway                   | HTTP request rate / latency             | Stateless replicas behind ALB; autoscaling                          |
| WebSocket Gateway             | Concurrent sockets, memory, network I/O | Multiple instances; graceful connection draining; reconnect support |
| Auth, User, Key, Conversation | API throughput / DB contention          | Stateless replicas; PostgreSQL HA; connection pooling               |
| Message Service               | Message write throughput / latency      | Replicas; durable idempotent writes; partitioned message store      |
| Fanout Service                | Consumer lag / recipient count          | Kafka consumer group; scale workers; protect against hot groups     |
| Delivery Service              | Pending backlog / oldest message age    | Consumer workers partitioned by recipient device                    |
| Presence Service              | Heartbeat rate / active connections     | Redis-backed ephemeral state; shard only when required              |
| Notification Service          | Provider throughput / retries           | Independent workers and provider-specific queues                    |

### Resilience requirements

- Run at least two stateless application instances across AZs in
  production.
- Use health/readiness checks, graceful shutdown and rolling or
  blue/green deployment.
- Apply bounded timeouts, circuit breakers, bulkheads, rate limits,
  exponential backoff with jitter and retry budgets.
- Reuse the same idempotency key on client retries; consumers must
  deduplicate stable event IDs.
- Never claim message acceptance until durable persistence succeeds.
- Kafka publication is not recipient delivery. Define `ACCEPTED`,
  `PUBLISHED`, `DELIVERED` and `READ` separately.
- Redis loss may make presence temporarily unknown; offline delivery
  must remain safe because its durable state is in the message/delivery
  store.
- Replication improves availability but does not replace backups.

### Backup and recovery starting policy

| System                 | Protection                                                    | Initial policy to validate                                                               |
| ---------------------- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| PostgreSQL             | Automated snapshots + WAL/PITR                                | Daily snapshot; 7–35 day PITR window depending on requirements                           |
| Cassandra              | Snapshots + incremental backups to separate storage           | Daily snapshot and incremental backups; test restore procedure                           |
| DynamoDB (if chosen)   | Point-in-time recovery + on-demand backups                    | Enable PITR and retain periodic backups                                                  |
| Redis                  | Optional persistence for cache recovery                       | Presence is rebuilt from reconnecting devices; never use Redis as offline-message source |
| Kafka                  | Replication across AZs + defined retention                    | Replication factor 3 where cluster size permits; archive critical events if required     |
| S3 backups             | Versioning, lifecycle, optional Object Lock/cross-region copy | Separate permissions from production application roles                                   |
| Infrastructure/secrets | IaC and managed secrets                                       | Version configuration; restrict and audit break-glass access                             |

Suggested planning objectives (must be validated by restore drills):
critical auth/message acceptance RPO <= 5 minutes and RTO <= 1 hour;
message history/key metadata RPO <= 15 minutes and RTO <= 4 hours;
rebuildable presence RTO 15–30 minutes. RPO is acceptable data-loss
window; RTO is target recovery time. These are initial targets, not
guarantees.

### Disaster recovery runbook

1. Declare incident and preserve logs/metrics/traces.
2. Identify process, node, AZ, region, corruption or security failure.
3. Choose failover or restore; understand replica lag and potential data
   loss before promotion.
4. Rebuild network and services from reviewed IaC.
5. Restore PostgreSQL and replay WAL/PITR to the approved recovery point.
6. Restore Cassandra/DynamoDB message and delivery state; validate
   schema, partitions, indexes and consistency.
7. Recover Kafka topics/configuration and replay from selected offsets;
   consumers must be idempotent.
8. Rebuild Redis presence from reconnecting clients and heartbeats.
9. Validate message retrieval, outbox age, pending delivery, membership
   authorization and ciphertext integrity.
10. Gradually restore traffic, monitor SLOs and record lessons learned.

### Production readiness gates

- [ ] Load tests cover concurrent sockets, messages/sec, group fanout,
  
      reconnect storms and offline backlog replay.
- [ ] Failure tests cover process/node/AZ loss and database, Redis and
  
      Kafka outages.
- [ ] Backups are encrypted, isolated and monitored.
- [ ] Restore drills prove measured RPO/RTO.
- [ ] Dashboards and alerts cover service latency, Kafka lag, DB health,
  
      pending delivery and backup freshness.
- [ ] Security review, threat model and E2EE implementation review are
  
      complete.

## 12. MVP acceptance criteria

The MVP is not complete until all of the following are demonstrated:

1. Alice and Bob register separate devices.
2. Alice establishes an E2EE session with Bob.
3. Alice sends a message; backend stores only ciphertext.
4. Bob receives and decrypts the message locally.
5. Alice, Bob, and Charlie exchange a group-encrypted message.
6. Removing Charlie prevents him from decrypting subsequent group
   messages according to the chosen protocol.
7. Online status reflects all active devices, not just one connection.
8. An offline recipient receives pending messages after reconnecting.
9. Duplicate client submissions and duplicate Kafka events do not
   create duplicate logical messages.
10. If Cassandra persists a message but Kafka is unavailable, the event
    remains recoverable and is eventually published.
11. If a publisher crashes after Kafka acknowledgement but before
    recording publication, duplicate publication is safely handled.
12. If Fanout or Delivery Service restarts, processing resumes without
    silently skipping events.
13. Automated integration and failure-injection tests pass.
14. Local Docker Compose startup and EC2 deployment instructions are
    verified.
15. A sender edits a message within 1 hour and deletes one for everyone within
    24 hours (gone from every device and server history); other users' edits
    and deletes are refused or ignored; a disappearing-messages timer set by any
    member removes messages from every device and the server on time.

## 13. Important design caveats

- Cassandra is query-driven, not a relational database. Design
  partition keys around actual reads and writes; avoid unbounded
  partitions and `ALLOW FILTERING`.
- Cassandra and Kafka do not share a distributed transaction. A
  same-partition Cassandra logged batch can atomically write the
  message and outbox record, but it does not atomically publish to
  Kafka.
- Outbox publication is generally at-least-once. Consumers must be
  idempotent.
- Kafka producer idempotence does not eliminate application-level
  duplicate publication after a process crash.
- Redis presence is ephemeral. Rebuild it from live connections and
  heartbeats after restart.
- A single-node Cassandra instance and single EC2 host are not highly
  available.
- E2EE does not automatically conceal metadata such as sender,
  recipient, timing, group membership, or message size.
