#!/usr/bin/env bash
# Creates a signing key pair in ./secrets (git-ignored) and writes the PUBLIC key to license-public-key.txt,
# which the Android build embeds. Use for development; for production run the same steps on an offline machine
# (docs/KEY_MANAGEMENT.md).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/dist/license-tool.jar"; test -f "$JAR" || { echo "build the tool first: scripts/build-license-tool.sh <kotlinc>"; exit 1; }
java -jar "$JAR" keygen --out-dir "$ROOT/secrets"
cp "$ROOT/secrets/license-public.b64" "$ROOT/license-public-key.txt"
echo "Wrote license-public-key.txt (public key; safe to commit). Private key stays in secrets/ (NEVER commit)."
