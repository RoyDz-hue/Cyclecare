#!/usr/bin/env bash
set -euo pipefail
# Offline only. Required cached compiler/dependency paths; no install or fetch.
: "${JAVA_HOME:?}" "${KOTLINC:?}" "${JSON_JAR:?}" "${CARGO:?}"
here=$(cd "$(dirname "$0")" && pwd); corpus=$1; out=$2
mkdir -p "$out"; out=$(cd "$out" && pwd)
python3 "$here/audit.py"
python3 "$here/prerequisites/writer-audit.py"
cd "$here/rust"
CARGO_NET_OFFLINE=true "$CARGO" run --offline --quiet -- "$corpus" "$out/projections.json" "$out/track-oracle.json" "$out/track-plans.json" "$out/care-oracle.json" "$out/settings-oracle.json" > "$out/plan.json" 2> "$out/rust.log"
"$KOTLINC" "$here/kotlin/Coordinator.kt" "$here/kotlin/Validation.kt" "$here/kotlin/HostileTests.kt" -cp "$JSON_JAR" -include-runtime -d "$out/coordinator.jar"
"$JAVA_HOME/bin/java" -cp "$out/coordinator.jar:$JSON_JAR" CoordinatorKt "$out/plan.json" "$out/synthetic" "$out/projections.json" "$corpus" > "$out/jvm.log"
cat "$out/jvm.log"; tail -1 "$out/rust.log"
