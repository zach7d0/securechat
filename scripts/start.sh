#!/usr/bin/env bash
# Starts the stack in dependency/readiness order. Usage: scripts/start.sh [--build] [--infra-only]
source "$(dirname "$0")/common.sh"
build=(); infra_only=false
for a in "$@"; do case $a in --build) build=(--build);; --infra-only) infra_only=true;; esac; done
log "starting datastores"
"${DC[@]}" up -d postgres cassandra redis kafka
for s in postgres redis kafka cassandra; do wait_healthy "$s" 420; log "$s healthy"; done
"${DC[@]}" up -d cassandra-init kafka-init
wait_healthy cassandra-init 300; wait_healthy kafka-init 180; log "keyspaces, roles and topics ready"
if [[ $infra_only == true ]]; then log "infrastructure ready"; exit 0; fi
log "starting application services"
"${DC[@]}" up -d "${build[@]}"
"$ROOT/scripts/health.sh" --wait
