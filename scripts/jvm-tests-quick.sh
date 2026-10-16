#!/usr/bin/env bash
# Convenience wrapper used during development in this sandbox.
set -uo pipefail
S=${S:?scratchpad}; K=$S/tools/kotlinc; J=/opt/gradle-8.14.3/lib/junit-4.13.2.jar:/opt/gradle-8.14.3/lib/hamcrest-core-1.3.jar
CORO=$K/lib/kotlinx-coroutines-core-jvm.jar; R=$(cd "$(dirname "$0")/.." && pwd); W=$S/wq; rm -rf $W; mkdir -p $W/{core,coretest,tool,tooltest,logic,logictest}
kc() { $K/bin/kotlinc -nowarn "$@" 2>&1 | grep -v '^Picked up'; true; }
kc $R/license-core/src/main/kotlin -d $W/core
kc -cp $W/core:$J $R/license-core/src/test/kotlin -d $W/coretest
kc -cp $W/core $R/license-tool/src/main/kotlin -d $W/tool
kc -cp $W/core:$W/tool:$W/coretest:$J $R/license-tool/src/test/kotlin -d $W/tooltest
kc -cp $CORO $R/app/src/main/java/com/smartmanager/whatsappshoot/logic -d $W/logic
kc -cp $W/logic:$J:$CORO $R/app/src/test/java -d $W/logictest
CLASSES=$(cd $W && find coretest tooltest logictest -name '*Test.class' | sed -E 's#^[^/]+/##; s#/#.#g; s#\.class$##' | sort)
java -cp $W/core:$W/coretest:$W/tool:$W/tooltest:$W/logic:$W/logictest:$K/lib/kotlin-stdlib.jar:$CORO:$J org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up' | grep -v -E '^\s+at (org\.junit|java\.base|jdk\.internal|kotlin\.)' 
