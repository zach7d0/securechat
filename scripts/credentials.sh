#!/usr/bin/env bash
# Prints the URLs and logins of a local SecureChat stack (values come from .env, which is git-ignored).
# Usage: scripts/credentials.sh [--save]   (--save also writes CREDENTIALS.local.md, git-ignored, mode 600)
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo "no .env yet — run scripts/setup.sh" >&2; exit 1; }
set -a; source .env; set +a

out=$(cat <<TXT
SecureChat — local logins (generated for THIS machine; keep private, never commit)

  Chat app        http://localhost:${CHAT_HTTP_PORT:-8100}
                  Create your own account with "Create an account" (no default user).

  Admin Portal    http://localhost:${ADMIN_HTTP_PORT:-8101}
                  username: ${ADMIN_BOOTSTRAP_USERNAME}
                  password: ${ADMIN_BOOTSTRAP_PASSWORD}
                  First login forces a new password$( [[ ${ADMIN_MFA_REQUIRED:-false} == true ]] && echo " and authenticator (TOTP) enrolment" ).
                  After you change it, the password above no longer works.

  Grafana         http://127.0.0.1:${GRAFANA_PORT:-8130}
                  username: admin
                  password: ${GRAFANA_ADMIN_PASSWORD}

  Prometheus      http://127.0.0.1:${PROMETHEUS_PORT:-8191}
                  username: ${PROMETHEUS_USER:-prometheus}
                  password: ${PROMETHEUS_PASSWORD}

  Dev tools (optional: docker compose --profile tools up -d kafka-ui pgadmin)
  Kafka UI        http://127.0.0.1:${KAFKA_UI_PORT:-8190}   (no login; localhost only)
  pgAdmin         http://127.0.0.1:${PGADMIN_PORT:-8192}
                  email: ${PGADMIN_EMAIL:-admin@example.com}   password: ${PGADMIN_PASSWORD:-}
                  (database password inside pgAdmin: POSTGRES_PASSWORD from .env)

All values live in .env; change one there (or re-run scripts/gen-secrets.sh --force on a fresh install)
and restart with scripts/start.sh. Grafana/Prometheus/tools are bound to 127.0.0.1 only.
TXT
)
echo "$out"
if [[ "${1:-}" == "--save" ]]; then
  umask 077
  { echo '```'; echo "$out"; echo '```'; } > CREDENTIALS.local.md
  echo; echo "saved to CREDENTIALS.local.md (git-ignored, readable only by you)"
fi
