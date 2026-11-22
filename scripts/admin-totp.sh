#!/usr/bin/env bash
# Prints the current 6-digit TOTP code for the local test admin (secret in secrets/admin-e2e.json).
# Dev convenience only — in real use, add the secret to an authenticator app instead.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import base64, hmac, hashlib, json, struct, time
s = json.load(open("secrets/admin-e2e.json"))["secret"]
key = base64.b32decode(s + "=" * (-len(s) % 8))
step = int(time.time()) // 30
h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
o = h[-1] & 15
code = (struct.unpack(">I", h[o:o + 4])[0] & 0x7fffffff) % 1_000_000
print(f"{code:06d}  (valid for {30 - int(time.time()) % 30}s; each code works once)")
PY
