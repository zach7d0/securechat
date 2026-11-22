#!/usr/bin/env bash
# Restores a backup made by scripts/backup.sh.
#   scripts/restore.sh --verify <dir>        check the manifest only
#   scripts/restore.sh --yes <dir>           DESTRUCTIVE: replaces PostgreSQL + Cassandra data
# Application services are stopped during the restore and started again afterwards.
source "$(dirname "$0")/common.sh"
mode=${1:-}; DIR=${2:-}
[[ -d "$DIR" ]] || { echo "usage: $0 --verify|--yes <backup-dir>" >&2; exit 1; }
( cd "$DIR" && sha256sum --quiet -c MANIFEST.sha256 ) || { echo "manifest check FAILED" >&2; exit 1; }
log "manifest OK ($DIR)"
[[ $mode == --verify ]] && exit 0
[[ $mode == --yes ]] || { echo "refusing to restore without --yes" >&2; exit 1; }

APPS=$("${DC[@]}" config --services | grep -E -- '-(service|gateway|gateway-2)$|^(frontend|admin-portal|edge)$' || true)
log "stopping application services"
"${DC[@]}" stop $APPS >/dev/null 2>&1 || true
"${DC[@]}" up -d postgres cassandra redis kafka >/dev/null
for s in postgres cassandra kafka; do wait_healthy "$s" 420; done
"${DC[@]}" up -d cassandra-init kafka-init >/dev/null
wait_healthy cassandra-init 300; wait_healthy kafka-init 180

for f in "$DIR"/postgres/*.dump; do
  db=$(basename "$f" .dump)
  "${DC[@]}" exec -T postgres pg_restore -U postgres --clean --if-exists --no-owner --role="${db%_db}_svc" -d "$db" < "$f" 2>/dev/null \
    || "${DC[@]}" exec -T postgres pg_restore -U postgres --clean --if-exists -d "$db" < "$f"
  log "postgres $db restored"
done

CQ=("${DC[@]}" exec -T -e CQLPW="$CASSANDRA_ADMIN_PASSWORD" cassandra bash -c)
for schema in "$DIR"/cassandra/*.schema.cql; do
  ks=$(basename "$schema" .schema.cql)
  # create tables that do not exist yet (fresh cluster), then load every table
  want=$({ grep -oE "^CREATE TABLE $ks\.[a-z_]+" "$schema" || true; } | sed "s/^CREATE TABLE $ks\.//" | sort)
  for attempt in 1 2 3 4 5; do
    err=$("${DC[@]}" exec -T -e CQLPW="$CASSANDRA_ADMIN_PASSWORD" cassandra bash -c "sed 's/CREATE TABLE /CREATE TABLE IF NOT EXISTS /; s/CREATE KEYSPACE /CREATE KEYSPACE IF NOT EXISTS /' > /tmp/schema.cql && cqlsh --request-timeout=60 -u scadmin -p \"\$CQLPW\" -f /tmp/schema.cql 2>&1 | grep -vE 'password on the command line|credentials file|^\s*$'; rm -f /tmp/schema.cql" < "$schema" || true)
    have=$("${CQ[@]}" "cqlsh -u scadmin -p \"\$CQLPW\" -e \"SELECT table_name FROM system_schema.tables WHERE keyspace_name='$ks'\" 2>/dev/null" | sed -n '4,$p' | { grep -vE '^\s*$|rows\)' || true; } | tr -d ' ' | sort) || have=''
    missing=$(comm -23 <(echo "$want") <(echo "$have"))
    [[ -z $missing ]] && break
    log "schema $ks incomplete (attempt $attempt): missing $(echo $missing) ${err:+— $err}"
    sleep 10
  done
  [[ -z $missing ]] || { echo "RESTORE FAILED: schema for $ks could not be created" >&2; exit 1; }
  log "schema $ks verified ($(echo "$want" | wc -l) tables)"
  for csv in "$DIR"/cassandra/$ks.*.csv; do
    t=$(basename "$csv" .csv); t=${t#$ks.}
    expected=$(($(wc -l < "$csv") - 1))
    (( expected < 0 )) && expected=0
    "${DC[@]}" exec -T cassandra bash -c "cat > /tmp/restore.csv" < "$csv"
    imported=-1
    for attempt in 1 2 3; do
      out=$("${CQ[@]}" "cqlsh -u scadmin -p \"\$CQLPW\" -e \"TRUNCATE $ks.$t\" >/dev/null 2>&1; cqlsh -u scadmin -p \"\$CQLPW\" -e \"COPY $ks.$t FROM '/tmp/restore.csv' WITH HEADER = true\" 2>&1" || true)
      imported=$(grep -oE '^[0-9]+ rows imported' <<<"$out" | grep -oE '^[0-9]+' || echo -1)
      [[ $expected -eq 0 && $imported -le 0 ]] && imported=0
      [[ $imported -eq $expected ]] && break
      log "cassandra $ks.$t: imported $imported of $expected rows (attempt $attempt), retrying"
      sleep 5
    done
    "${CQ[@]}" "rm -f /tmp/restore.csv"
    [[ $imported -eq $expected ]] || { echo "RESTORE FAILED: $ks.$t imported $imported of $expected rows" >&2; exit 1; }
    log "cassandra $ks.$t restored ($imported/$expected rows verified)"
  done
done
log "starting application services"
"${DC[@]}" up -d >/dev/null
"$ROOT/scripts/health.sh" --wait >/dev/null && log "restore complete; stack healthy"
