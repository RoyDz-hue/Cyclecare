#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?}" "${KOTLINC:?}" "${AOSP_JSON_JAR:?}" "${ROOM_COMMON_JAR:?}"
here=$(cd "$(dirname "$0")" && pwd);repo=$(cd "$here/../../.." && pwd);main="$repo/../.."
# repo is spike/offline-owner-local parent? derive owning root directly.
root=$(cd "$here/../../../.." && pwd)
local="$root/Androidapp/app/src/main/java/com/cyclecare/app"
"$KOTLINC" "$here/CareConversationPolicy.kt" "$here/NaturalLanguageLogParser.kt" "$here/DisabledBridge.kt" "$here/CareOracle.kt" "$here/../Codec.kt" "$local/data/local/CycleDayLog.kt" "$local/data/local/CycleSettings.kt" "$local/data/model/CycleModels.kt" "$local/data/local/OnboardingProfile.kt" "$here/../Stubs.kt" -cp "$AOSP_JSON_JAR:$ROOM_COMMON_JAR" -include-runtime -d "$1/care-oracle.jar"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$1/care-oracle.jar:$AOSP_JSON_JAR" CareOracleKt > "$1/care-oracle.json"
