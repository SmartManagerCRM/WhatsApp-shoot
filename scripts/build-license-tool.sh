#!/usr/bin/env bash
# Builds the standalone owner utility dist/license-tool.jar WITHOUT Gradle (only JDK 17+ and kotlinc 2.0.x needed).
# Usage: scripts/build-license-tool.sh /path/to/kotlinc
set -euo pipefail
KOTLINC="${1:?path to kotlinc dir (the one containing bin/kotlinc)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/dist"
"$KOTLINC/bin/kotlinc" -nowarn "$ROOT/license-core/src/main/kotlin" "$ROOT/license-tool/src/main/kotlin" \
  -include-runtime -d "$ROOT/dist/license-tool.jar" 2>&1 | grep -v '^Picked up' || true
test -s "$ROOT/dist/license-tool.jar"
echo "built $ROOT/dist/license-tool.jar"
echo "run:  java -jar dist/license-tool.jar help"
