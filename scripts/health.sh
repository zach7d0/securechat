#!/usr/bin/env bash
# Prints container health and verifies datastore readiness in depth.
# Usage: scripts/health.sh [--wait]   (--wait blocks until every running service is healthy)
source "$(dirname "$0")/common.sh"
if [[ "${1:-}" == "--wait" ]]; then
  for s in $("${DC[@]}" ps --format '{{.Service}}'); do wait_healthy "$s" 420 || exit 1; done
fi
fail=0
check() { if eval "$2" >/dev/null 2>&1; then printf '  %-28s OK\n' "$1"; else printf '  %-28s FAIL\n' "$1"; fail=1; fi; }
echo "Containers:"
"${DC[@]}" ps -a --format '  {{printf "%-26s" .Service}} {{.State}} {{.Health}}'
echo "Datastores:"
check "postgres databases" "${DC[*]} exec -T postgres psql -U postgres -tAc \"SELECT count(*) FROM pg_database WHERE datname IN ('auth_db','user_db','key_db','conversation_db','monitoring_db','admin_db')\" | grep -qx 6"
check "cassandra keyspaces" "${DC[*]} exec -T cassandra cqlsh -u scadmin -p '$CASSANDRA_ADMIN_PASSWORD' -e 'DESCRIBE KEYSPACES' | grep -q securechat_delivery"
check "redis (ACL admin)" "${DC[*]} exec -T redis redis-cli --no-auth-warning --user admin --pass '$REDIS_ADMIN_PASSWORD' ping | grep -q PONG"
check "kafka topics" "${DC[*]} exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q chat.message.accepted"
for svc in $("${DC[@]}" ps --format '{{.Service}}' | grep -E -- '-(service|gateway)$' || true); do
  port=$("${DC[@]}" exec -T "$svc" printenv MANAGEMENT_SERVER_PORT 2>/dev/null || true)
  [[ -n $port ]] && check "$svc readiness" "${DC[*]} exec -T $svc curl -fsS localhost:$port/actuator/health/readiness"
done
exit $fail
