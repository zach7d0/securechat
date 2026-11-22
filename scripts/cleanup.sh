#!/usr/bin/env bash
# Interactive clean-up of a local SecureChat stack.
#
#   scripts/cleanup.sh              # menu
#   scripts/cleanup.sh --dry-run    # show what each choice would do, change nothing
#   scripts/cleanup.sh --option 4 --confirm permanent-delete    # non-interactive (automation)
#
#   1  Stop services                        (keeps containers and all data)
#   2  Remove containers and networks       (keeps all data)
#   3  Clear monitoring data                 Prometheus, Grafana, pgAdmin        → type: permanent-delete
#   4  Clear databases (all chat data)       PostgreSQL, Cassandra, Redis, Kafka → type: permanent-delete
#   5  Clear EVERYTHING                      containers, all volumes, built images, .env, secrets/,
#                                            backups/, test results, CREDENTIALS.local.md → type: deleteAll
#
# Only resources declared by this project's docker-compose.yml are touched (its volumes and the
# securechat/*:local images it builds) — never other Docker projects on the machine.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

dry=false; option=""; confirm=""; noninteractive=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run) dry=true ;;
    --option) option=${2:-}; noninteractive=true; shift ;;
    --confirm) confirm=${2:-}; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

bold=$'\033[1m'; red=$'\033[31m'; green=$'\033[32m'; dim=$'\033[2m'; off=$'\033[0m'
say() { printf '%s\n' "$*"; }
done_msg() { if $dry; then say "${dim}Dry run — nothing was changed.${off}"; else say "$*"; fi; }
run() { if $dry; then printf '%s[dry-run]%s %s\n' "$dim" "$off" "$*"; else "$@"; fi; }

command -v docker >/dev/null && docker compose version >/dev/null 2>&1 || { say "Docker Compose v2 is required." >&2; exit 1; }

# Compose needs every ${VAR:?} to be set even to stop things. Without .env (e.g. after option 5),
# borrow the placeholders from .env.example just for these commands.
if [[ -f .env ]]; then set -a; source .env; set +a
else set -a; source .env.example; set +a; fi
DC=(docker compose -f "$ROOT/docker-compose.yml")
[[ -n "${COMPOSE_EXTRA_FILE:-}" ]] && DC+=(-f "$COMPOSE_EXTRA_FILE")
DCA=("${DC[@]}" --profile tools)                     # include optional tools (kafka-ui, pgadmin)
PROJECT=${COMPOSE_PROJECT_NAME:-securechat}

DB_VOLUMES=(pg-data cassandra-data redis-data kafka-data)
DB_SERVICES=(postgres cassandra redis kafka)
MON_VOLUMES=(prometheus-data grafana-data pgadmin-data)
MON_SERVICES=(prometheus grafana pgadmin)

VOLUME_SIZES=""
vol_size() {   # size of a project volume (from `docker system df -v`, no image download), or "absent"
  local v="${PROJECT}_$1"
  docker volume inspect "$v" >/dev/null 2>&1 || { echo "absent"; return; }
  [[ -n $VOLUME_SIZES ]] || VOLUME_SIZES=$(docker system df -v --format json 2>/dev/null \
    | python3 -c "import json,sys; [print(v['Name'], v.get('Size','?')) for v in json.load(sys.stdin).get('Volumes') or []]" 2>/dev/null || true)
  awk -v n="$v" '$1==n {print $2; f=1} END {if (!f) print "present"}' <<<"$VOLUME_SIZES"
}
list_volumes() { for v in "$@"; do printf '    %-18s %s\n' "$v" "$(vol_size "$v")"; done; }

built_images() {
  "${DC[@]}" config --format json 2>/dev/null | python3 -c \
    "import json,sys; c=json.load(sys.stdin); print('\n'.join(sorted({s['image'] for s in c['services'].values() if 'build' in s and 'image' in s})))"
}

require_phrase() {   # $1 = phrase the user must type exactly
  local phrase=$1 typed
  if [[ -n $confirm ]]; then typed=$confirm
  else
    printf '\n%sThis cannot be undone.%s Type %s%s%s to continue (anything else cancels): ' "$red$bold" "$off" "$bold" "$phrase" "$off"
    read -r typed
  fi
  [[ $typed == "$phrase" ]] || { say "Cancelled — nothing was deleted."; exit 1; }
}
require_yes() {
  $noninteractive && return 0                        # --option given: 1–2 are non-destructive
  printf '%s [y/N] ' "$1"; local a; read -r a
  [[ $a == y || $a == Y ]] || { say "Cancelled."; exit 1; }
}

remove_volumes() {   # stop+remove the services using them, then delete the project volumes
  local -n vols=$1 svcs=$2
  run "${DCA[@]}" rm --stop --force "${svcs[@]}"
  for v in "${vols[@]}"; do
    if docker volume inspect "${PROJECT}_$v" >/dev/null 2>&1; then run docker volume rm "${PROJECT}_$v"; fi
  done
}

if [[ -z $option ]]; then
  cat <<MENU

${bold}SecureChat clean-up${off}  ${dim}(project: ${PROJECT}$($dry && echo ", dry run — nothing will change"))${off}

  1) Stop services                        keeps containers and all data
  2) Remove containers and networks       keeps all data
  3) Clear monitoring data                Prometheus, Grafana, pgAdmin         ${red}deletes data${off}
  4) Clear databases (all chat data)      PostgreSQL, Cassandra, Redis, Kafka  ${red}deletes data${off}
  5) Clear EVERYTHING                     containers, volumes, images, secrets, backups  ${red}deletes all${off}
  q) Quit

MENU
  printf 'Choose [1-5, q]: '; read -r option
fi

case $option in
  1)
    say "Stops every SecureChat container. Data and containers are kept; start again with scripts/start.sh."
    require_yes "Stop all services?"
    run "${DCA[@]}" stop
    done_msg "${green}Stopped.${off} Data kept." ;;
  2)
    say "Removes SecureChat containers and networks. All data volumes are kept; scripts/setup.sh --skip-build recreates them."
    require_yes "Remove containers and networks?"
    run "${DCA[@]}" down --remove-orphans
    done_msg "${green}Containers and networks removed.${off} Data kept." ;;
  3)
    say "Deletes monitoring data (metrics history, Grafana settings/users, pgAdmin settings):"
    list_volumes "${MON_VOLUMES[@]}"
    say "Chat data (accounts, messages, keys) is NOT touched."
    require_phrase permanent-delete
    remove_volumes MON_VOLUMES MON_SERVICES
    done_msg "${green}Monitoring data cleared.${off} Run scripts/start.sh to bring the services back (fresh)." ;;
  4)
    say "Deletes ALL CHAT DATA — every account, device, public key, conversation, group, message and pending delivery:"
    list_volumes "${DB_VOLUMES[@]}"
    say "Stops the whole stack first (the services depend on these databases). Secrets in .env are kept."
    say "${dim}Note: browsers keep their old local keys; users should clear this site's data (or use a new"
    say "browser profile) and register again.${off}"
    require_phrase permanent-delete
    run "${DCA[@]}" stop
    remove_volumes DB_VOLUMES DB_SERVICES
    done_msg "${green}Databases cleared.${off} Run scripts/start.sh — the init jobs recreate empty databases, keyspaces and topics." ;;
  5)
    say "Deletes EVERYTHING this project created on this machine:"
    say "  • all SecureChat containers and networks"
    say "  • all data volumes:"
    list_volumes "${DB_VOLUMES[@]}" "${MON_VOLUMES[@]}"
    say "  • built images: $(built_images | tr '\n' ' ')"
    for p in .env secrets backups tests/system/evidence tests/failure/evidence CREDENTIALS.local.md; do
      [[ -e $p ]] && say "  • $p ($(du -sh "$p" 2>/dev/null | cut -f1))"
    done
    say "Kept: the source code, docs/implementation/ and anything outside this project."
    say "Afterwards scripts/setup.sh does a completely fresh install (new secrets and passwords)."
    require_phrase deleteAll
    run "${DCA[@]}" down --volumes --remove-orphans
    mapfile -t imgs < <(built_images)
    for i in "${imgs[@]}"; do docker image inspect "$i" >/dev/null 2>&1 && run docker image rm -f "$i"; done
    run rm -rf .env secrets backups tests/system/evidence tests/failure/evidence CREDENTIALS.local.md
    done_msg "${green}Everything cleared.${off} Run scripts/setup.sh for a fresh install." ;;
  q|Q|"") say "Nothing done."; exit 0 ;;
  *) say "Unknown choice: $option" >&2; exit 2 ;;
esac
