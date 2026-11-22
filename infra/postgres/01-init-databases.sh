#!/usr/bin/env bash
# One database + one login role per service. Each role owns only its own database; PUBLIC is
# revoked so no service can connect to another service's database.
set -euo pipefail
create() {
  local db=$1 role=$2 pw=$3
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${role}') THEN
    CREATE ROLE ${role} LOGIN PASSWORD '${pw}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
  ELSE
    ALTER ROLE ${role} WITH PASSWORD '${pw}';
  END IF;
END \$\$;
SELECT 'CREATE DATABASE ${db} OWNER ${role}' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${db}')\gexec
REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE ${db} TO ${role};
SQL
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO ${role};
SQL
}
create auth_db         auth_svc         "$AUTH_DB_PASSWORD"
create user_db         user_svc         "$USER_DB_PASSWORD"
create key_db          key_svc          "$KEY_DB_PASSWORD"
create conversation_db conversation_svc "$CONVERSATION_DB_PASSWORD"
create monitoring_db   monitoring_svc   "$MONITORING_DB_PASSWORD"
create admin_db        admin_svc        "$ADMIN_DB_PASSWORD"
echo "securechat databases ready"
