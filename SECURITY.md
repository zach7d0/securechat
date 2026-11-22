# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report privately through GitHub: on the repository page open the **Security** tab → **Report a vulnerability**
(GitHub private vulnerability reporting). Include:

- affected component (chat client, Admin Portal, a backend service, deployment scripts) and version/commit;
- steps to reproduce or a proof of concept;
- impact you believe it has (e.g. plaintext/key exposure, auth bypass, cross-tenant access, DoS).

We aim to acknowledge reports within **7 days** and to agree a disclosure timeline with you (typically up to
**90 days**). Please give us a reasonable chance to fix the issue before disclosing it.

## Supported versions

This is a young project; only the latest commit on the default branch receives security fixes.

## Scope

In scope: the code in this repository — client-side E2EE (`frontend/src/lib/crypto`, `frontend/src/lib/messaging`),
backend services, the Admin Portal, the edge/gateway configuration, and the setup/deployment scripts.

Out of scope: vulnerabilities in third-party dependencies with no SecureChat-specific impact (please report those
upstream), attacks that require a compromised end-user device or browser, and findings against deployments you do
not own.

## Known limitations (not vulnerabilities)

These are documented design limits — see [TERMS.md](TERMS.md) and [docs/E2EE_CLIENT.md](docs/E2EE_CLIENT.md):

- no independent audit of the project or of the Signal-protocol TypeScript port / `ts-mls`;
- browser key storage is not hardware-backed (XSS = key compromise; strict CSP mitigates);
- metadata (who/when/size/device ids) is visible to the server;
- automatic device history sync trusts the device directory (anyone who can register a device on an account can
  receive up to a month of history per request; the sending device shows a "sent to new device — Revoke" notice);
- shared group history is vouched for by the member who shared it, not by the original senders;
- the single-host deployment is not highly available.

## Hardening checklist for operators

- Run `scripts/setup.sh` (or `scripts/gen-secrets.sh`) to generate unique secrets; never use example values.
- Set `ADMIN_MFA_REQUIRED=true` in production and serve everything over TLS (put a TLS-terminating reverse proxy in
  front of the edge on ports 8100/8101).
- Keep Grafana, Prometheus, Kafka UI and pgAdmin bound to localhost or behind a VPN.
- Rotate the bootstrap admin password at first login (enforced) and remove unused admin accounts.
