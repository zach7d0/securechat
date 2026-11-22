#!/usr/bin/env bash
# Stops all containers; named volumes (data) are kept.
source "$(dirname "$0")/common.sh"
"${DC[@]}" --profile tools --profile observability stop
log "stopped (data volumes preserved)"
