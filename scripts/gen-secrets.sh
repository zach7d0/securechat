#!/usr/bin/env bash
# Creates .env (from .env.example) with random secrets and RSA signing keys in ./secrets.
# Idempotent: an existing .env / key is kept unless --force is given.
# Any value can be chosen instead of a random one by exporting it first, e.g.
#   ADMIN_BOOTSTRAP_PASSWORD='my-long-passphrase' GRAFANA_ADMIN_PASSWORD='...' scripts/gen-secrets.sh
set -euo pipefail
cd "$(dirname "$0")/.."
force=false; [[ "${1:-}" == "--force" ]] && force=true

if [[ -f .env && $force == false ]]; then
  echo ".env already exists (use --force to regenerate)"
else
  cp .env.example .env
  while grep -q '=change-me$' .env; do
    key=$(grep -m1 '=change-me$' .env | cut -d= -f1)
    if [[ -n "${!key:-}" ]]; then val=${!key}                          # value supplied by the caller
    elif [[ $key == ADMIN_MFA_ENCRYPTION_KEY ]]; then val=$(openssl rand -base64 32 | tr -d '\n')
    else val=$(openssl rand -base64 36 | tr -d '\n/+=' | cut -c1-40); fi
    [[ $val == *"|"* ]] && { echo "$key must not contain '|'" >&2; exit 1; }
    sed -i "s|^${key}=change-me$|${key}=${val}|" .env
  done
  chmod 600 .env
  echo "wrote .env"
fi

mkdir -p secrets && chmod 700 secrets
for name in auth-jwt admin-jwt; do
  f="secrets/${name}-private.pem"
  if [[ -f $f && $force == false ]]; then echo "$f exists"; continue; fi
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$f" 2>/dev/null
  chmod 644 "$f"   # read by the non-root service user inside its container; directory is 700 on the host
  echo "wrote $f"
done

# Prometheus basic auth (hashed password file)
"$(dirname "$0")/gen-prometheus-auth.sh"
