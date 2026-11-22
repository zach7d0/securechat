#!/usr/bin/env bash
# Online backup of durable state. Usage: scripts/backup.sh [output-dir]   (default: ./backups)
#  - PostgreSQL: pg_dump -Fc per service database (consistent per database)
#  - Cassandra:  schema (DESCRIBE) + cqlsh COPY TO per table (MVP-sized data; use snapshots/Medusa at scale)
#  - Redis/Kafka are NOT backed up: presence is rebuilt from reconnects; the outbox is the
#    recovery source for unpublished events and consumers are idempotent.
#  - Secrets (.env, ./secrets) are excluded: back them up separately to a secrets manager.
source "$(dirname "$0")/common.sh"
OUT=${1:-$ROOT/backups}
TS=$(date -u +%Y%m%dT%H%M%SZ)
DIR="$OUT/securechat-$TS"
mkdir -p "$DIR/postgres" "$DIR/cassandra"
chmod 700 "$OUT" "$DIR"
log "backup → $DIR"
for db in auth_db user_db key_db conversation_db monitoring_db admin_db; do
  "${DC[@]}" exec -T postgres pg_dump -U postgres -Fc "$db" > "$DIR/postgres/$db.dump"
  log "postgres $db $(du -h "$DIR/postgres/$db.dump" | cut -f1)"
done
CQ=("${DC[@]}" exec -T -e CQLPW="$CASSANDRA_ADMIN_PASSWORD" cassandra bash -c)
for ks in securechat_message securechat_delivery; do
  "${CQ[@]}" "cqlsh -u scadmin -p \"\$CQLPW\" -e 'DESCRIBE KEYSPACE $ks' 2>/dev/null" > "$DIR/cassandra/$ks.schema.cql"
  tables=$("${CQ[@]}" "cqlsh -u scadmin -p \"\$CQLPW\" -e \"SELECT table_name FROM system_schema.tables WHERE keyspace_name='$ks'\" 2>/dev/null" \
           | sed -n '4,$p' | grep -vE '^\s*$|rows\)' | tr -d ' ')
  for t in $tables; do
    "${CQ[@]}" "rm -f /tmp/$ks.$t.csv && cqlsh -u scadmin -p \"\$CQLPW\" -e \"COPY $ks.$t TO '/tmp/$ks.$t.csv' WITH HEADER = true\" >/dev/null 2>&1; cat /tmp/$ks.$t.csv; rm -f /tmp/$ks.$t.csv" \
      > "$DIR/cassandra/$ks.$t.csv"
    log "cassandra $ks.$t $(($(wc -l < "$DIR/cassandra/$ks.$t.csv") - 1)) rows"
  done
done
( cd "$DIR" && find . -type f ! -name MANIFEST.sha256 -print0 | sort -z | xargs -0 sha256sum > MANIFEST.sha256 )
echo "$TS" > "$OUT/LATEST"
log "backup complete: $DIR ($(du -sh "$DIR" | cut -f1)); verify with scripts/restore.sh --verify $DIR"
