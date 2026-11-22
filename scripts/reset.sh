#!/usr/bin/env bash
# DESTRUCTIVE: removes containers AND all data volumes. Requires --yes.
source "$(dirname "$0")/common.sh"
[[ "${1:-}" == "--yes" ]] || { echo "This deletes ALL SecureChat data. Re-run with --yes." >&2; exit 1; }
"${DC[@]}" --profile tools --profile observability down -v --remove-orphans
log "stack and volumes removed"
