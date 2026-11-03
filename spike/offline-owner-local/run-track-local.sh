#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?}" "${KOTLINC:?}" "${AOSP_JSON_JAR:?}" "${ROOM_COMMON_JAR:?}" "${JSON_JAR:?}" "${CARGO:?}"
here=$(cd "$(dirname "$0")" && pwd);repo=$(cd "$here/../.." && pwd);out=$1;corpus=$2
# Dedicated output dir only; prevent stale fixture evidence from earlier versions.
case "$out" in /|"$HOME"|"$repo"|"$here") echo 'unsafe output directory' >&2;exit 2;; esac
rm -rf -- "$out";mkdir -p "$out";out=$(cd "$out" && pwd);main="$repo/Androidapp/app/src/main/java/com/cyclecare/app/data/local"
python3 "$here/oracle/check-extract.py" "$repo"
"$KOTLINC" "$here/oracle/Codec.kt" "$here/oracle/AnchorOracle.kt" "$here/oracle/TrackOracle.kt" "$main/CycleDayLog.kt" "$main/CycleSettings.kt" "$main/OnboardingProfile.kt" -cp "$AOSP_JSON_JAR:$ROOM_COMMON_JAR" -include-runtime -d "$out/track-oracle.jar"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out/track-oracle.jar:$AOSP_JSON_JAR" TrackOracleKt > "$out/track-oracle.json"
JAVA_HOME="$JAVA_HOME" KOTLINC="$KOTLINC" AOSP_JSON_JAR="$AOSP_JSON_JAR" ROOM_COMMON_JAR="$ROOM_COMMON_JAR" bash "$here/oracle/care/run-oracle.sh" "$out"
"$KOTLINC" "$here/oracle/Codec.kt" "$here/oracle/SettingsOracle.kt" "$main/CycleDayLog.kt" "$main/CycleSettings.kt" "$main/OnboardingProfile.kt" -cp "$AOSP_JSON_JAR:$ROOM_COMMON_JAR" -include-runtime -d "$out/settings-oracle.jar"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out/settings-oracle.jar:$AOSP_JSON_JAR" SettingsOracleKt > "$out/settings-oracle.json"
JAVA_HOME="$JAVA_HOME" KOTLINC="$KOTLINC" JSON_JAR="$JSON_JAR" CARGO="$CARGO" bash "$here/run-local.sh" "$corpus" "$out"
"$KOTLINC" "$here/kotlin/TrackStore.kt" "$here/kotlin/UnifiedTests.kt" "$here/kotlin/Validation.kt" "$here/kotlin/Coordinator.kt" "$here/kotlin/HostileTests.kt" "$here/oracle/care/CareConversationPolicy.kt" "$here/oracle/care/NaturalLanguageLogParser.kt" "$here/oracle/care/DisabledBridge.kt" "$here/oracle/Stubs.kt" "${main%/data/local}/data/model/CycleModels.kt" "$here/oracle/Codec.kt" "$here/oracle/AnchorOracle.kt" "$main/CycleDayLog.kt" "$main/CycleSettings.kt" "$main/OnboardingProfile.kt" -cp "$AOSP_JSON_JAR:$ROOM_COMMON_JAR" -include-runtime -d "$out/track-store.jar"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out/track-store.jar:$AOSP_JSON_JAR" UnifiedTestsKt "$out/track-plans.json" "$out/track-synthetic" "$out/projections.json" "$corpus" > "$out/track-store.log"
cat "$out/track-store.log"
