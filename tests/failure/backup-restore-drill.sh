#!/usr/bin/env bash
# DESTRUCTIVE drill (local/staging only): seed → backup → delete ALL containers + volumes → restore → verify.
# Usage: tests/failure/backup-restore-drill.sh --yes
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[[ "${1:-}" == "--yes" ]] || { echo "This wipes all local data. Re-run with --yes." >&2; exit 1; }
STATE=$(mktemp -d)
cd "$ROOT/frontend"
echo "== 1. seed data (offline recipient → pending deliveries)"
npx tsx system/drill.ts before "$STATE"
echo "== 2. backup"
"$ROOT/scripts/backup.sh" "$ROOT/backups" | tail -1
B="$ROOT/backups/securechat-$(cat "$ROOT/backups/LATEST")"
echo "== 3. wipe everything (containers + volumes)"
"$ROOT/scripts/reset.sh" --yes
docker volume ls --format '{{.Name}}' | grep -c '^securechat_' | xargs -I{} echo "remaining securechat volumes: {}"
echo "== 4. restore into a fresh environment"
"$ROOT/scripts/restore.sh" --yes "$B"
docker compose -f "$ROOT/docker-compose.yml" up -d --force-recreate edge >/dev/null
sleep 10
echo "== 5. verify"
npx tsx system/drill.ts after "$STATE"
