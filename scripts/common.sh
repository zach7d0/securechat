#!/usr/bin/env bash
# Shared helpers for SecureChat scripts.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[[ -f .env ]] || { echo "missing .env — run scripts/gen-secrets.sh first" >&2; exit 1; }
set -a; source .env; set +a
DC=(docker compose -f "$ROOT/docker-compose.yml")
[[ -n "${COMPOSE_EXTRA_FILE:-}" ]] && DC+=(-f "$COMPOSE_EXTRA_FILE")
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
# Waits until a compose service reports healthy (or a one-shot exits 0).
wait_healthy() {
  local svc=$1 timeout=${2:-300} t=0 state="" st health code
  while (( t < timeout )); do
    state=$("${DC[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}' 2>/dev/null | awk -F'|' -v s="$svc" '$1==s{print $2"|"$3"|"$4}')
    IFS='|' read -r st health code <<<"$state"
    # Running + healthy, or running without a healthcheck (empty health), counts as ready.
    [[ $st == running && ( $health == healthy || -z $health ) ]] && return 0
    if [[ $st == exited ]]; then
      [[ $code == 0 ]] && return 0
      echo "$svc exited with code $code" >&2; return 1
    fi
    sleep 3; t=$((t+3))
  done
  echo "$svc not healthy after ${timeout}s (state: $state)" >&2; return 1
}
