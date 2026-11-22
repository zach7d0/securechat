# Contributing to SecureChat

Thanks for helping! By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE) and that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Security issues: **do not** open a public issue — see [SECURITY.md](SECURITY.md).

## Getting started

Requirements: Docker Engine + Compose v2 (~10 GB free RAM), Java 21 + Maven 3.9, Node 22, `openssl`.

```bash
scripts/setup.sh          # one click: secrets → build → start → health check → prints your logins
scripts/credentials.sh    # show the logins again later
scripts/stop.sh           # stop (keeps data) · scripts/cleanup.sh: clean-up menu (clear data / everything)
```

## Project layout

| Path | What |
|---|---|
| `backend/` | Maven reactor: `platform/` (contracts, service support, test support) and `services/*` (Spring Boot 4, Java 21) |
| `frontend/` | Chat SPA (React + TypeScript). `src/lib/crypto` = client-side E2EE; `system/` = system tests against the running stack |
| `admin-portal/` | Admin Portal SPA |
| `infra/`, `scripts/` | datastore init, edge/nginx, observability, setup and operations scripts |
| `docs/` | architecture, key management & encryption, client E2EE, runbooks |

Coding conventions: [docs/CONVENTIONS.md](docs/CONVENTIONS.md) and `.editorconfig`.

## Tests — run what you touch

```bash
cd backend && mvn -pl services/<service> verify        # unit + Testcontainers integration tests (needs Docker)
cd backend && mvn verify                                # everything
cd frontend && npx tsc -b && npx vitest run             # client typecheck + E2EE unit tests
cd admin-portal && npm run build

# against the running stack (scripts/setup.sh first); load .env so tests can read service credentials
set -a && . ./.env && set +a
cd frontend && npx tsx system/e2e.ts                    # end-to-end incl. plaintext scan of every datastore
cd frontend && npx tsx system/group-late-device.ts      # (and the other system/*.ts suites relevant to your change)
```

System and failure tests write their results to `tests/system/evidence/` and `tests/failure/evidence/`
(git-ignored) — paste the relevant summary into your PR instead of committing them.

Changes to cryptography, key handling, authentication, or the admin trust boundary need tests that show the
security property (e.g. "server never sees plaintext", "wrong key fails", "role X is refused"), not only the
happy path.

## Pull requests

- One topic per PR; describe the problem, the change, and how you verified it (paste test output).
- Keep the security invariants: no plaintext or private keys on the server, no custom cryptography, admin sees
  metadata only, secrets never committed (`.env`, `secrets/`, `*.pem` are git-ignored).
- New third-party dependencies need a short justification (security-sensitive project); pin exact versions in
  `frontend/` and `admin-portal/`.
- Database changes are new Flyway migrations (`V<n>__name.sql`) — never edit an applied migration.
- Update the relevant docs in `docs/` when behaviour, APIs or the trust model change.
- Commit messages: imperative mood, short subject line, body explains *why*.

## Reporting bugs and proposing features

Open a GitHub issue with steps to reproduce, expected vs actual behaviour, and logs (`docker compose logs <service>`)
with any secrets removed.
