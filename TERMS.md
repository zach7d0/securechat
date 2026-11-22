# Terms of Use and Disclaimer

SecureChat is free and open-source software, licensed under the [Apache License 2.0](LICENSE). These terms
summarise how the project may be used and the limits of what it promises. They are not legal advice; where
they differ from the license, **the license prevails**.

## 1. No warranty

The software is provided **"AS IS", without warranties or conditions of any kind**, express or implied,
including fitness for a particular purpose (Apache-2.0 §7). The contributors are **not liable** for any
damages arising from its use (Apache-2.0 §8).

## 2. Security status — read before relying on it

- SecureChat is a **reference implementation / MVP**. It has **not had an independent security audit**.
- The cryptographic libraries it uses (a TypeScript port of the Signal Protocol and `ts-mls` for MLS) have
  not been independently audited for this project; `ts-mls` carries a local patch
  (`frontend/patches/`).
- Keys are stored in the browser (IndexedDB). They are not hardware-backed; a cross-site-scripting bug or a
  compromised browser/device exposes them.
- End-to-end encryption protects message **content**, not **metadata** (who talks to whom, when, message
  sizes, device ids are visible to the server operator).
- Automatic device-to-device history sync and group-history sharing have documented trust trade-offs — see
  [docs/E2EE_CLIENT.md](docs/E2EE_CLIENT.md).
- The default single-host deployment is **not highly available** and keeps all data on one machine.

**Do not advertise a deployment as "secure" or "end-to-end encrypted" to real users without your own
security review, threat model and operational hardening.**

## 3. Operators' responsibilities

If you deploy SecureChat for anyone else, you — not the project contributors — are the operator and are
responsible for:

- complying with the laws that apply to you and your users (privacy/data-protection such as GDPR or PDPA,
  lawful-interception, export-control and telecommunications rules, consumer law);
- your own terms of service and privacy notice for your users;
- secrets management: generate your own secrets (`scripts/setup.sh` / `scripts/gen-secrets.sh`), never reuse
  example values, and never commit `.env` or `secrets/`;
- TLS, backups, monitoring, access control to the Admin Portal, Grafana and Prometheus, and patching;
- handling abuse reports and data-subject requests.

## 4. Acceptable use

Do not use SecureChat to break the law, to harass or harm people, to distribute malware, or to infringe others'
rights. The Admin Portal is designed to show metadata only — do not modify it to collect message content or keys
from users without their informed consent.

## 5. Trademarks

The name "SecureChat" and project logos are not licensed for use in ways that suggest endorsement of a fork or
deployment (Apache-2.0 §6). Please use a different name for substantially modified distributions.

## 6. Contributions

Contributions are accepted under the Apache License 2.0 (Apache-2.0 §5). See [CONTRIBUTING.md](CONTRIBUTING.md)
and the [Code of Conduct](CODE_OF_CONDUCT.md).
