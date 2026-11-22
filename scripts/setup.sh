#!/usr/bin/env bash
# One-click local setup: checks prerequisites, generates secrets/keys (.env, secrets/*), builds all images,
# starts the stack in dependency order, waits until healthy and prints the logins.
#
#   scripts/setup.sh                 # first run, or re-run any time (keeps existing secrets and data)
#   scripts/setup.sh --skip-build    # start without rebuilding images
#
# Choose your own passwords instead of random ones on the FIRST run, e.g.:
#   ADMIN_BOOTSTRAP_PASSWORD='a-long-passphrase' GRAFANA_ADMIN_PASSWORD='...' scripts/setup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
skip_build=false
for a in "$@"; do case $a in --skip-build) skip_build=true;; -h|--help) sed -n '2,10p' "$0"; exit 0;; esac; done

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

step "Checking prerequisites"
command -v docker >/dev/null || fail "Docker is not installed (https://docs.docker.com/engine/install/)"
docker info >/dev/null 2>&1 || fail "Docker is not running, or this user cannot use it (try: sudo usermod -aG docker \$USER, then log in again)"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required ('docker compose', not 'docker-compose')"
command -v openssl >/dev/null || fail "openssl is required to generate secrets"
mem_gb=$(awk '/MemAvailable/ {printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0)
if [[ ${mem_gb:-0} -gt 0 && ${mem_gb} -lt 8 ]]; then
  echo "warning: only ~${mem_gb} GB RAM available; the full stack wants ~10 GB (it may start slowly or fail)"
fi
echo "ok: $(docker --version | cut -d, -f1), $(docker compose version --short 2>/dev/null || echo compose v2)"

step "Generating secrets (.env, JWT signing keys, Prometheus auth) — existing ones are kept"
scripts/gen-secrets.sh

step "Building and starting the stack (first run downloads images and builds everything; can take 10–20 min)"
if [[ $skip_build == true ]]; then scripts/start.sh; else scripts/start.sh --build; fi

step "Health check"
scripts/health.sh || echo "some checks failed — see above, and 'docker compose logs <service>'"

step "Done"
scripts/credentials.sh
echo
echo "Tip: scripts/credentials.sh shows these again (--save writes CREDENTIALS.local.md, git-ignored)."
echo "     scripts/stop.sh stops the stack (data kept) · scripts/reset.sh --yes wipes everything."
