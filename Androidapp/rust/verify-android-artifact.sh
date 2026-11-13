#!/usr/bin/env bash
# Fail-closed verification gate for a FRESH Android ARM artifact of
# cyclecare-core. A stale, cached, copied or fallback artifact is NOT proof.
# Usage: verify-android-artifact.sh <triple> <ndk_home> <out_hash_file>
# Every assertion is field-aware and fail-closed; one OK line per gate.
set -euo pipefail
T="${1:?triple, e.g. aarch64-linux-android}"
NDK="${2:?ANDROID_NDK_HOME}"
OUT="${3:?output hash file}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fail() { echo "FAIL: $*" >&2; exit 1; }

# Gate 0 - explicit triple -> ABI/ELF mapping. Unknown triples are rejected,
# never silently mapped (reviewer: no any-T-always-arm64).
case "$T" in
  aarch64-linux-android)   ABI="arm64-v8a";  ELFCLASS="ELF64"; ELFMACHINE="AArch64" ;;
  armv7-linux-androideabi) ABI="armeabi-v7a"; ELFCLASS="ELF32"; ELFMACHINE="ARM" ;;
  i686-linux-android)      ABI="x86";        ELFCLASS="ELF32"; ELFMACHINE="Intel 80386" ;;
  x86_64-linux-android)    ABI="x86_64";     ELFCLASS="ELF64"; ELFMACHINE="Advanced Micro Devices X86-64" ;;
  *) fail "unknown triple '$T' - no reviewed ABI mapping" ;;
esac
echo "OK: triple $T -> ABI $ABI ($ELFCLASS/$ELFMACHINE)"

case "$(uname -s)" in
  Linux)  HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) fail "UNSUPPORTED host OS: $(uname -s)" ;;
esac
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
READELF="$TOOLS/llvm-readelf"; NM="$TOOLS/llvm-nm"
[ -x "$READELF" ] || fail "llvm-readelf missing at $READELF"
[ -x "$NM" ] || fail "llvm-nm missing at $NM"

# Gate 1 - the artifact exists and provably belongs to THIS run. The caller
# wiped the triple's target dir before building AND recorded a gate-start
# timestamp; BOTH are required. A missing gate-start file fails closed.
GATE_START_FILE="$ROOT/../build-logs/.gate-start"
[ -f "$GATE_START_FILE" ] || fail "gate-start record missing at $GATE_START_FILE - cannot prove freshness"
GATE_START="$(cat "$GATE_START_FILE")"
[ -n "$GATE_START" ] || fail "gate-start record empty"
SO="$ROOT/rust/core-ffi/target/$T/release/libcyclecare_core.so"
[ -f "$SO" ] || SO="$ROOT/rust/core-ffi/target/$T/release/libcyclecare_core_ffi.so"
[ -f "$SO" ] || fail "no cdylib artifact for $T (build produced nothing)"
SO_MTIME=$(stat -f %m "$SO" 2>/dev/null || stat -c %Y "$SO")
[ "$SO_MTIME" -ge "$GATE_START" ] || \
  fail "artifact predates this run (mtime $SO_MTIME < gate start $GATE_START)"
echo "OK: artifact fresh for this run (mtime $SO_MTIME >= gate start $GATE_START)"

# Gate 2 - ELF identity: exact Class/Machine/Type header fields.
HDR="$("$READELF" -h "$SO")"
grep -q "Class:[[:space:]]*$ELFCLASS\$" <<< "$HDR" || fail "ELF class is not $ELFCLASS"
grep -q "Machine:[[:space:]]*$ELFMACHINE\$" <<< "$HDR" || fail "ELF machine is not $ELFMACHINE"
grep -q "Type:[[:space:]]*DYN " <<< "$HDR" || fail "ELF type is not DYN (shared object)"
echo "OK: ELF $ELFCLASS $ELFMACHINE DYN"

# Gate 3 - 16 KB page alignment: the Align FIELD of every LOAD program
# header must be exactly 0x4000 (field-aware parse, not a line grep), and
# at least one LOAD row must exist (no vacuous pass).
NLOAD=$("$READELF" -lW "$SO" | awk '$1=="LOAD"' | wc -l | tr -d ' ')
[ "$NLOAD" -ge 1 ] || fail "no LOAD segments read from $SO"
"$READELF" -lW "$SO" | awk '$1=="LOAD" && $NF!="0x4000" {print "  bad LOAD row:", $0; bad=1} END{exit bad}' \
  || fail "LOAD rows whose Align field is not 0x4000"
echo "OK: all $NLOAD LOAD segments Align field == 0x4000"

# Gate 4 - the exact JNI exports the host calls, matched on the symbol NAME
# field of defined dynamic symbols (not a substring over the whole output).
for SYM in \
  Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeCoreHandshake \
  Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeCall \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativePlan \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeComplete \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeSettings \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeBatch \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeOutbox \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeConflict \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeRemote \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeResolution \
  Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeErase ; do
  "$NM" -D --defined-only "$SO" | awk -v s="$SYM" '$NF==s {f=1} END{exit !f}' \
    || fail "missing defined dynamic JNI export: $SYM"
  echo "OK: export $SYM"
done

# Gate 5 - record the exact bytes for the downstream APK gate.
SHA=$(shasum -a 256 "$SO" | awk '{print $1}')
SIZE=$(stat -f %z "$SO" 2>/dev/null || stat -c %s "$SO")
echo "$SHA  lib/$ABI/libcyclecare_core.so  bytes=$SIZE  source=$SO" > "$OUT"
echo "OK: sha256 $SHA ($SIZE bytes) recorded to $OUT"
echo "ARTIFACT GATE PASS"
