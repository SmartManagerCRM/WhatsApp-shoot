#!/usr/bin/env bash
# Compiles and runs the pure-JVM unit tests WITHOUT Gradle (useful when Google Maven is unreachable).
# Usage: scripts/run-jvm-tests.sh <kotlinc-dir> <android.jar> [workdir]
# Requires: JDK 17+, kotlinc 2.0.x, junit-4.13.2 + hamcrest-core on the classpath (found in the Gradle distribution lib/).
set -euo pipefail
KOTLINC="$1/bin/kotlinc"; ANDROID_JAR="$2"; W="${3:-$(mktemp -d)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_LIB="$(dirname "$(readlink -f "$(command -v gradle)")")/../lib"
JUNIT="$(ls "$GRADLE_LIB"/junit-4*.jar | head -1)"; HAM="$(ls "$GRADLE_LIB"/hamcrest*.jar | tr '\n' ':')"
STD="$1/lib/kotlin-stdlib.jar"; CORO="$1/lib/kotlinx-coroutines-core-jvm.jar"
mkdir -p "$W"/{core,coretest,tool,tooltest,logic,logictest}
KC() { "$KOTLINC" -nowarn "$@" 2>&1 | grep -v '^Picked up' || true; }
KC "$ROOT/license-core/src/main/kotlin" -d "$W/core"
KC -cp "$W/core:$JUNIT:$HAM" "$ROOT/license-core/src/test/kotlin" -d "$W/coretest"
KC -cp "$W/core" "$ROOT/license-tool/src/main/kotlin" -d "$W/tool"
KC -cp "$W/core:$W/tool:$W/coretest:$JUNIT:$HAM" "$ROOT/license-tool/src/test/kotlin" -d "$W/tooltest"
KC -cp "$CORO" "$ROOT/app/src/main/java/com/smartmanager/whatsappshoot/logic" -d "$W/logic"
KC -cp "$W/logic:$JUNIT:$HAM:$CORO" "$ROOT/app/src/test/java" -d "$W/logictest"
CP="$W/core:$W/coretest:$W/tool:$W/tooltest:$W/logic:$W/logictest:$STD:$CORO:$JUNIT:$HAM"
CLASSES=$(cd "$W" && find coretest tooltest logictest -name '*Test.class' | sed -E 's#^[^/]+/##; s#/#.#g; s#\.class$##')
java -cp "$CP" org.junit.runner.JUnitCore $CLASSES
