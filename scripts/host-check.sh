#!/usr/bin/env bash
# Host resource check (cron): disk and memory thresholds + unhealthy containers. Non-zero exit on problems.
set -uo pipefail
cd "$(dirname "$0")/.."
status=0
disk=$(df --output=pcent /var/lib/docker 2>/dev/null | tail -1 | tr -dc '0-9')
mem=$(free | awk '/Mem:/ {printf "%d", ($3/$2)*100}')
[[ ${disk:-0} -ge ${DISK_WARN:-80} ]] && { echo "$(date -Is) WARN disk ${disk}% used"; status=1; }
[[ ${mem:-0} -ge ${MEM_WARN:-90} ]] && { echo "$(date -Is) WARN memory ${mem}% used"; status=1; }
bad=$(docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | awk '$2=="unhealthy"{print $1}')
[[ -n $bad ]] && { echo "$(date -Is) WARN unhealthy: $bad"; status=1; }
[[ $status -eq 0 ]] && echo "$(date -Is) OK disk=${disk}% mem=${mem}%"
exit $status
