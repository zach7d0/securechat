#!/bin/bash
# One-shot: replaces the default superuser, creates keyspaces and one role per owning service.
# Tables are created by each service's own CQL migrations at startup (service-owned schema).
set -euo pipefail
HOST=${CASSANDRA_HOST:-cassandra}
cq() { cqlsh "$HOST" 9042 "$@" 2> >(grep -v -e "password on the command line" -e "credentials file" -e "^$" >&2); }

for i in $(seq 1 60); do
  if cq -u scadmin -p "$CASSANDRA_ADMIN_PASSWORD" -e "SELECT now() FROM system.local" >/dev/null 2>&1; then AUTH=(-u scadmin -p "$CASSANDRA_ADMIN_PASSWORD"); break; fi
  if cq -u cassandra -p cassandra -e "SELECT now() FROM system.local" >/dev/null 2>&1; then AUTH=(-u cassandra -p cassandra); break; fi
  echo "waiting for cassandra ($i)"; sleep 3
done
[[ -n "${AUTH[*]:-}" ]] || { echo "cassandra not reachable"; exit 1; }

if [[ "${AUTH[1]}" == cassandra ]]; then
  cq "${AUTH[@]}" -e "CREATE ROLE IF NOT EXISTS scadmin WITH SUPERUSER = true AND LOGIN = true AND PASSWORD = '$CASSANDRA_ADMIN_PASSWORD';"
  AUTH=(-u scadmin -p "$CASSANDRA_ADMIN_PASSWORD")
  # Disable the well-known default superuser.
  cq "${AUTH[@]}" -e "ALTER ROLE cassandra WITH SUPERUSER = false AND LOGIN = false AND PASSWORD = '$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=')';"
fi

# Service roles: create, or rotate the password when the role already exists.
role() {
  if cq "${AUTH[@]}" -e "SELECT role FROM system_auth.roles WHERE role = '$1'" 2>/dev/null | grep -q " $1"; then
    cq "${AUTH[@]}" -e "ALTER ROLE $1 WITH PASSWORD = '$2';"
  else
    cq "${AUTH[@]}" -e "CREATE ROLE $1 WITH LOGIN = true AND PASSWORD = '$2';"
  fi
}

cq "${AUTH[@]}" <<CQL
-- Single-node MVP: SimpleStrategy RF=1 (NOT highly available; see docs).
CREATE KEYSPACE IF NOT EXISTS securechat_message
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': ${CASSANDRA_RF:-1}} AND durable_writes = true;
CREATE KEYSPACE IF NOT EXISTS securechat_delivery
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': ${CASSANDRA_RF:-1}} AND durable_writes = true;

CQL
role message_svc "$MESSAGE_CASSANDRA_PASSWORD"
role delivery_svc "$DELIVERY_CASSANDRA_PASSWORD"
cq "${AUTH[@]}" -e "GRANT ALL PERMISSIONS ON KEYSPACE securechat_message TO message_svc; GRANT ALL PERMISSIONS ON KEYSPACE securechat_delivery TO delivery_svc;"
echo "cassandra keyspaces and roles ready"
