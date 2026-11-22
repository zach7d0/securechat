# Client-side E2EE — Library Selection, Key Storage and Session Safety

Everything in this document runs **in the browser**. The backend receives only protocol ciphertext,
public keys and routing metadata. Code: `frontend/src/lib/crypto/*`, `frontend/src/lib/messaging/engine.ts`.

## 1. Library selection

| Need | Selected | Why | Risks / limits (accepted for MVP) |
|---|---|---|---|
| One-to-one (X3DH + Double Ratchet) | `@privacyresearch/libsignal-protocol-typescript` 0.0.16 | Pure TypeScript port of libsignal's protocol layer that runs in browsers (Signal's official `@signalapp/libsignal-client` ships native Node bindings and has no supported browser/WASM build). Supports identity/signed/one-time prekeys, PreKeyWhisper/Whisper messages, skipped-message keys, XEdDSA. GPL-3.0 (compatible with this project; must be reviewed before any distribution change). | Community-maintained port, X3DH (not PQXDH), no external audit of this port. Production E2EE claims require an independent review (`SECURECHAT_ARCHITECTURE.md` §23). |
| Group (MLS, RFC 9420) | `ts-mls` 1.6.4, ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` | Actively maintained TypeScript implementation of RFC 9420 (TreeKEM, commits, welcomes, key packages, epoch secret retention). No custom group scheme. | Young library; same review requirement. |
| Fingerprints | Signal displayable fingerprint (v0) re-implemented on WebCrypto SHA-512 | byte-identical to the library's generator (`fingerprint.test.ts`), 40× faster | — |
| Local at-rest wrapping | WebCrypto AES-256-GCM, non-extractable key | browser-native | see §3 |

No cryptographic primitive or protocol serialisation was invented. SecureChat-specific code only
(a) frames per-device Signal envelopes for transport, (b) pads plaintext to 64-byte buckets before
the protocol's AEAD (`payload.ts`), and (c) binds each device's MLS signature key to its Signal
identity key with an XEdDSA signature produced by the library.

## 2. Device bootstrap (implemented in `keyManager.ts`)

1. Generate device id, Signal identity key pair and registration id **locally**; persist them in the
   encrypted key store first (provisioning is resumable).
2. `POST /api/v1/devices` (User Service) → `POST /api/v1/auth/device-session` (device-bound token).
3. Generate the MLS signature key; sign `"securechat-mls-binding-v1:" userId ":" deviceId ":" mlsPub`
   with the identity key.
4. Upload **public** material only: identity key + binding, signed prekey (+ signature), 100
   one-time prekeys, 20 MLS key packages + 1 last-resort key package.
5. Replenish when the server reports `prekeys_low` (< 20 prekeys / < 5 key packages); rotate the
   signed prekey weekly, keeping old private halves 30 days for in-flight session setups.

## 3. Browser key storage — constraints and threat model

- Records (identity/prekey/session private keys, MLS group state, decrypted history) are stored in
  IndexedDB, each AES-256-GCM encrypted under a **non-extractable** CryptoKey that is itself kept in
  IndexedDB. Copying the profile directory does not reveal key bytes in the clear.
- **This is not equivalent to hardware-backed storage** (iOS Keychain/Secure Enclave, Android
  Keystore). Malicious script running in the page can still *use* the wrapping key to decrypt
  records; an attacker with the unlocked OS account can run such script.
- XSS mitigations (because XSS ⇒ key compromise):
  - strict CSP (`script-src 'self'`, no `unsafe-eval`, no third-party scripts/CDNs, `object-src 'none'`,
    `frame-ancestors 'none'`), served by `frontend/nginx.conf`;
  - React escaping only — no `dangerouslySetInnerHTML`; message bodies are rendered as text;
  - all crypto dependencies are bundled and pinned (`package-lock.json`); no runtime code loading;
  - `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Permissions-Policy` restrictive.
- The refresh token is kept in `localStorage` so sessions survive reloads (XSS can read it; it is
  rotated on every use and reuse revokes the whole session). Access tokens are 5-minute JWTs.
- Non-secret UI preferences (chat theme, welcome-tour progress and its "show automatically" switch) are kept
  in plain `localStorage` per device; they contain no keys, message content or contacts and are never sent
  to the server.
- Revoking a device does not erase keys from a stolen browser; it stops key distribution, revokes
  sessions, closes sockets and (for groups) triggers removal of the device's MLS leaf.

## 4. Session-state safety

- **One engine per device**: Web Locks (`navigator.locks`) allow a single tab to run the engine;
  other tabs show "open in another tab". The gateway additionally closes an older connection of the
  same device (newest wins).
- **Per-conversation mutex** (`KeyedMutex`) serialises every operation that reads and writes ratchet
  or MLS state (encrypt, decrypt, commit processing); state is persisted before the result is used.
- **Exactly-once processing**: each delivery is decrypted once; the message id is recorded in the
  `seen` store *after* the plaintext is stored and *before* the DELIVERED ACK, so redelivery is ACKed
  without touching ratchet state.
- **MLS own commits**: the staged state is persisted (`mlspending`) before submission; on restart it is
  applied only if the server's commit log shows *this device* won that epoch, otherwise discarded.
- **Ordering**: commits come from the totally ordered log (epoch compare-and-set on the server);
  an application message for a newer epoch triggers commit sync first; late messages from a recent
  older epoch decrypt from retained epoch secrets (ts-mls key retention), older ones show as
  "sent before this device joined".

## 5. Failure handling

| Case | Behaviour |
|---|---|
| Invalid signed-prekey signature | `processPreKey` rejects; message not sent |
| Unknown/changed identity key for a known device | never silently accepted (`IdentityChangedError`); UI shows a safety-number warning, user must approve |
| Missing session | X3DH from a claimed bundle; no one-time prekey left → X3DH without OPK |
| Duplicate / out-of-order Signal messages | duplicate rejected by the ratchet + `seen` dedupe; out-of-order via skipped keys |
| Tampered ciphertext / wrong key | AEAD authentication failure → "Unable to decrypt", still ACKed (no infinite redelivery) |
| Stale recipient device list | server answers `DEVICE_LIST_STALE`; client refreshes identities and re-encrypts |
| Stale MLS epoch on send | server answers `EPOCH_MISMATCH`; client syncs commits and re-encrypts |
| Revoked device | gateway closes 4403; engine stops; next login provisions a new device |
| Member removed / left | fanout excludes them; their client forgets the group state; remaining clients reconcile leaves |

## 6. Tests

- `frontend/src/test/*.test.ts` (Vitest, real libraries): X3DH/ratchet incl. restarts, no private key
  or plaintext reaches the "server", tampering and wrong keys fail, offline ordering, identity change
  detection, safety numbers, no-OPK fallback, per-device envelopes, replay rejection, out-of-order,
  mutex serialisation; MLS group decrypt, removal re-key, credential binding, offline catch-up, new
  device via sync commit (no access to earlier history), revoked device removal, key update, late
  previous-epoch message, crash recovery of own commits.
- `frontend/system/e2e.ts` and the other `frontend/system/*.ts` suites: the same engine against the running stack
  (results are written to `tests/system/evidence/`, git-ignored).

## Device-to-device history sync (automatic)

Messages are encrypted only for the devices that existed when they were sent, so a new device cannot
read older history by itself. It gets it from the user's other devices; the server only relays
ciphertext (user-service, tables `device_transfers` / `device_transfer_chunks`).

1. **Request (new device, automatic):** on first start, and whenever "Load older messages" reaches
   months this device cannot read, it generates a one-time X25519 HPKE key pair and signs
   `securechat-history-request-v2:<user>:<device>:<fromMs>:<toMs>:<ephemeralPub>` with its Signal
   identity key. A window is at most 31 days; ≤ 3 open and ≤ 30 requests per user per day.
2. **Answer (any other device of the user, automatic):** every 15 s it checks for requests; for each
   one whose signature verifies against the new device's identity key, a device that holds messages in
   the window claims it (server compare-and-set: first device wins) and sends — no code comparison.
   A bad signature is declined.
3. **Send:** gzip(JSON of local decrypted messages in the window) → HPKE base mode (RFC 9180,
   DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, `info` bound to the transfer id) → ≤150 KB
   chunks; a manifest (ids, `enc`, SHA-256, counts) signed by the source identity key.
4. **Import (new device):** verifies manifest signature, ids and hash, decrypts, imports without
   duplicates and deletes the server copy.
5. **Notice:** the sending device shows "N messages were sent to your new device ‘name’ — Not you?
   Revoke" (also as a sidebar banner) until acknowledged.

**Trust model (changed from the earlier manual flow):** removing the 20-digit code comparison means the
answering device trusts the server's device directory. Anyone who can register a device on the account
(someone with the password, or a compromised server inventing a device) receives up to a month of
history per request. Mitigations: signed, window-bound requests; month-sized windows and a daily cap;
the visible notice with one-click revoke on every sending device; password change signs other devices out.

"Load older messages" pages server history one month per click. Group messages from before the user
joined are not shown as locked bubbles but counted for one "You can't see messages from before you
joined" notice; messages from before this device existed are requested automatically as above.

## Sharing group history with new members

MLS gives a new member only the epochs after their Add commit. When adding someone, an OWNER/ADMIN chooses
what earlier history they may see: Don't share / last 100 messages / last 10 min, 1 h, 10 h, 1 day, 7 days,
30 days / all.

1. **Grant:** the admin's device creates a server grant (`history_grants`, admin-only) and sends a
   `history-grant` MLS application message in the new epoch (scope, grantedAt, target users). MLS
   authenticates the admin; receivers also check the sender is OWNER/ADMIN.
2. **Fulfil (any member device):** a device that holds the history in scope claims the grant
   (compare-and-set: exactly one wins). The admin's device goes first when it holds all of it; members
   holding all of it answer after 3–15 s, partial holders after 30–45 s, so the most complete copy wins.
3. **Share:** the claimer seals gzip(JSON) with AES-256-GCM under a fresh key (AAD
   `securechat-history-share-v1:<conversationId>:<shareId>`), uploads ciphertext to `history_shares`, and
   sends the key, hash and grant id as a `history-share` MLS message in the same epoch.
4. **Import (new member):** accepts a share only for a grant it received from an admin, re-applies the
   grant's scope to whatever arrives, verifies the hash, decrypts, and imports without overwriting anything
   it can read. Imported messages carry `sharedBy` and are labelled "shared by <member>".

Trust: shared messages are vouched for by the member who shared them, not by their original senders; the
whole group is told what was shared. Nobody can fulfil → the member sees "You can't see messages from
before you joined".

## Library patch: ts-mls 1.6.4

`frontend/patches/ts-mls+1.6.4.patch` (applied by `patch-package` on install and in the Docker build) fixes the
ratchet-tree validation run when joining a group: per RFC 9420 §7.9.2 each non-blank node between an unmerged leaf
and a node listing it must *contain* that leaf in its `unmerged_leaves`; 1.6.4 required the lists to be *equal*,
rejecting valid trees after several adds in different subtrees. Same fix as upstream ts-mls 2.0. Drop the patch when
upgrading to ts-mls ≥ 2.0 — note that 2.0 cannot decode group state saved by 1.6.4, so that upgrade needs a migration.


## Edit, delete and disappearing messages

All three are **encrypted control messages** inside the existing Signal / MLS channel (`frontend/src/lib/crypto/payload.ts`);
they never render as bubbles, and the server sees only what it already sees for any message (sender, time, size).

| Payload | Fields | Accepted by receivers when |
| --- | --- | --- |
| `text` | `+ expiresIn?` (seconds) | always; the message expires at **server time of sending + expiresIn** |
| `edit` | `target` (message id), `body` | from the **original sender**, ≤ **1 h** after the target (server timestamps, + 2 min skew) |
| `delete` | `target` | from the **original sender**, ≤ **24 h** after the target |
| `timer` | `seconds` (0 = off) | from **any member**; shown as a system line ("Bob set disappearing messages to 1 hour") |

Rules live in `frontend/src/lib/messaging/rules.ts`. Windows use the server's `createdAt`, never a sender's clock.
A notice that arrives before its target is held and applied when the target arrives. Forged or late notices are ignored.

Server side:

- **Delete for everyone** also calls `DELETE /api/v1/conversations/{id}/messages/{messageId}?at=<createdAt>`
  (message-service): only the original sender, only a current member, only within 24 h (`409 DELETE_WINDOW_EXPIRED`
  after). The row is removed from history, so new devices and "Load older" never fetch it.
- **Disappearing messages**: the chat setting is stored in conversation-service
  (`PUT /api/v1/conversations/{id}/disappearing {seconds}`, any member, `conversations.disappearing_seconds`) so new
  devices and members learn it. Each message carries `expiresInSeconds`; message-service writes it with
  `USING TTL`, so Cassandra drops the ciphertext on time (default 365 days otherwise). Devices sweep expired messages
  every 30 s and drop already-expired ones they receive late (or via history sync).
- **Delete for me** is local only (the id is remembered so history sync never re-imports it).

Limits, as in every E2EE messenger: a recipient can always copy or screenshot a message before it is edited,
deleted or expires, and a modified client can ignore edit/delete notices. Undelivered copies for an offline device
stay in the delivery queue until that device acknowledges or the queue entry expires; the device then discards them.
