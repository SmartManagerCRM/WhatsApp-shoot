#!/usr/bin/env bash
# Compile-checks the non-Compose Android sources against android.jar (API 34) using stubs for
# R / BuildConfig / androidx FileProvider / MainActivity. This is a *type check*, not a build:
# it proves the platform glue compiles against the real Android API surface, nothing more.
# Usage: S=<workdir containing tools/kotlinc, tools/android34.jar, stubs/> scripts/compile-check-platform.sh
set -uo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
K=$S/tools/kotlinc
rm -rf $S/out/app; mkdir -p $S/out/app $S/out/core
$K/bin/kotlinc -nowarn $R/license-core/src/main/kotlin -d $S/out/core 2>&1 | grep -v '^Picked up'
$K/bin/kotlinc -nowarn -Xjava-source-roots=$S/stubs -cp $K/lib/kotlinx-coroutines-core-jvm.jar:$S/out/core:$S/tools/android34.jar \
  $(find $R/app/src/main/java -name '*.kt' -not -path '*/ui/*') $S/stubs/R.kt $S/stubs/com/smartmanager/whatsappshoot/ui/MainActivity.kt -d $S/out/app 2>&1 | grep -v '^Picked up'
echo "exit-ish: $(find $S/out/app -name '*.class' | wc -l) classes produced"
