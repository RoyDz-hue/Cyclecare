#!/usr/bin/env bash
# Fail-closed APK composition gate. packageRustCore silently skips missing
# targets ("Kotlin fallback"), and AGP may transform (strip) native inputs:
# neither is proof. The FINAL packaged ELF is always validated (identity,
# alignment, dynamic JNI exports); the hash is accepted only by exact byte
# equality with the verified fresh artifact OR by byte-exact reproduction
# of the packaged file from the preserved raw input via an llvm-strip mode
# (the raw input is re-hashed and must still equal the recorded hash before
# any reproduction is trusted). DEX bridge presence uses dexdump class
# definitions when available, else a LABELED narrowed descriptor-string
# check. Usage:
#   verify-apk-rust-content.sh <apk> <hashfile> <ndk_home> <apk_record_out>
set -euo pipefail
APK="${1:?path to APK}"
HASHFILE="${2:?hash file from verify-android-artifact.sh}"
NDK="${3:?ANDROID_NDK_HOME}"
APKREC="${4:?apk sha record output file}"
fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$APK" ] || fail "APK not found: $APK"
[ -f "$HASHFILE" ] || fail "hash file not found: $HASHFILE"
WANT_SHA=$(awk '{print $1}' "$HASHFILE")
WANT_PATH=$(awk '{print $2}' "$HASHFILE")
SOURCE=$(sed -n 's/.*source=\([^ ]*\).*/\1/p' "$HASHFILE")
[ -n "$WANT_SHA" ] || fail "empty expected sha in $HASHFILE"

# Gate 0 - explicit ABI path -> ELF identity mapping; unknown paths rejected.
case "$WANT_PATH" in
  lib/arm64-v8a/libcyclecare_core.so)  ELFCLASS="ELF64"; ELFMACHINE="AArch64" ;;
  lib/armeabi-v7a/libcyclecare_core.so) ELFCLASS="ELF32"; ELFMACHINE="ARM" ;;
  lib/x86/libcyclecare_core.so)        ELFCLASS="ELF32"; ELFMACHINE="Intel 80386" ;;
  lib/x86_64/libcyclecare_core.so)     ELFCLASS="ELF64"; ELFMACHINE="Advanced Micro Devices X86-64" ;;
  *) fail "unexpected packaged path '$WANT_PATH' - no reviewed ABI mapping" ;;
esac
echo "OK: packaged path $WANT_PATH -> $ELFCLASS/$ELFMACHINE"

# Gate 1 - exactly ONE matching entry in the APK zip listing.
N=$(unzip -l "$APK" | awk -v p="$WANT_PATH" '$4==p' | wc -l | tr -d ' ')
[ "$N" = "1" ] || fail "expected exactly 1 APK entry $WANT_PATH, found $N"
echo "OK: exactly one APK entry $WANT_PATH"

# Record the APK's own bytes (reviewer: APK file SHA record).
APKSHA=$(shasum -a 256 "$APK" | awk '{print $1}')
APKBYTES=$(stat -f %z "$APK" 2>/dev/null || stat -c %s "$APK")
echo "$APKSHA  $(basename "$APK")  bytes=$APKBYTES" > "$APKREC"
echo "OK: APK sha256 $APKSHA ($APKBYTES bytes) recorded to $APKREC"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$APK" "$WANT_PATH" -d "$TMP" || fail "cannot extract $WANT_PATH"
FINAL="$TMP/$WANT_PATH"
GOT_SHA=$(shasum -a 256 "$FINAL" | awk '{print $1}')

# Gate 2 - the FINAL packaged ELF itself: identity, alignment, exports.
case "$(uname -s)" in
  Linux)  HOST_TAG="linux-x86_64" ;; Darwin) HOST_TAG="darwin-x86_64" ;; *) fail "UNSUPPORTED host OS" ;;
esac
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
READELF="$TOOLS/llvm-readelf"; NM="$TOOLS/llvm-nm"; STRIP="$TOOLS/llvm-strip"
HDR="$("$READELF" -h "$FINAL")"
grep -q "Class:[[:space:]]*$ELFCLASS\$" <<< "$HDR" || fail "packaged ELF is not $ELFCLASS"
grep -q "Machine:[[:space:]]*$ELFMACHINE\$" <<< "$HDR" || fail "packaged ELF is not $ELFMACHINE"
grep -q "Type:[[:space:]]*DYN " <<< "$HDR" || fail "packaged ELF is not DYN"
NLOAD=$("$READELF" -lW "$FINAL" | awk '$1=="LOAD"' | wc -l | tr -d ' ')
[ "$NLOAD" -ge 1 ] || fail "no LOAD segments read from packaged ELF"
"$READELF" -lW "$FINAL" | awk '$1=="LOAD" && $NF!="0x4000" {bad=1} END{exit bad}' \
  || fail "packaged ELF has LOAD rows whose Align field is not 0x4000"
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
  "$NM" -D --defined-only "$FINAL" | awk -v s="$SYM" '$NF==s {f=1} END{exit !f}' \
    || fail "packaged ELF missing dynamic JNI export: $SYM"
done
echo "OK: final packaged ELF is $ELFCLASS/$ELFMACHINE/DYN, all $NLOAD LOAD Align 0x4000, 11 required JNI exports in .dynsym"

# Gate 3 - byte provenance: exact equality, or EXACT reproduction of the
# packaged bytes from the preserved raw input via a named strip mode. The
# raw input is re-hashed first and must still equal the recorded hash
# (post-Gradle mutation of the source breaks provenance and fails closed).
if [ "$GOT_SHA" = "$WANT_SHA" ]; then
  echo "OK: packaged .so byte-identical to verified artifact ($GOT_SHA)"
else
  [ -n "$SOURCE" ] && [ -f "$SOURCE" ] || \
    fail "packaged sha $GOT_SHA != verified $WANT_SHA and raw input $SOURCE unavailable - cannot ground the transformation"
  SRC_NOW=$(shasum -a 256 "$SOURCE" | awk '{print $1}')
  [ "$SRC_NOW" = "$WANT_SHA" ] || \
    fail "raw input mutated after artifact gate (recorded $WANT_SHA, now $SRC_NOW) - provenance broken"
  [ -x "$STRIP" ] || fail "llvm-strip missing - cannot test strip reproduction"
  MATCHED=""
  for MODE in "--strip-debug" "--strip-unneeded" "--strip-all"; do
    cp "$SOURCE" "$TMP/probe.so"
    "$STRIP" $MODE "$TMP/probe.so" 2>/dev/null || continue
    PSHA=$(shasum -a 256 "$TMP/probe.so" | awk '{print $1}')
    if [ "$PSHA" = "$GOT_SHA" ]; then MATCHED="$MODE"; break; fi
  done
  [ -n "$MATCHED" ] || \
    fail "packaged sha $GOT_SHA != input $WANT_SHA and no llvm-strip mode reproduces it - transformation NOT symbol-stripping evidence"
  echo "OK: packaged .so is exactly llvm-strip $MATCHED of the verified input (input $WANT_SHA -> packaged $GOT_SHA); transformation proven"
fi

# Gate 4 - bridge classes in DEX: dexdump class definitions preferred
# (dump written to a file first - a piped grep -q would SIGPIPE the dumper
# under pipefail); otherwise a LABELED narrowed descriptor-string check.
DEXDIR="$TMP/dex"; mkdir -p "$DEXDIR"
unzip -o -q "$APK" 'classes*.dex' -d "$DEXDIR" || fail "no DEX files in APK"
DEXDUMP=$(ls "$ANDROID_HOME"/build-tools/*/dexdump 2>/dev/null | sort | tail -1 || true)
if [ -n "$DEXDUMP" ] && [ -x "$DEXDUMP" ]; then
  DUMP="$TMP/dexdump.txt"; : > "$DUMP"
  for D in "$DEXDIR"/classes*.dex; do
    "$DEXDUMP" -f "$D" >> "$DUMP" 2>&1 || fail "dexdump failed on $D"
  done
  for CLS in 'Lcom/cyclecare/app/rust/CycleCareCoreBridge;' 'Lcom/cyclecare/app/data/local/rust/RustTrackNative;'; do
    grep -qF "Class descriptor  : '$CLS'" "$DUMP" \
      || fail "class definition $CLS not found by dexdump in any DEX"
    echo "OK: dexdump class definition $CLS"
  done
else
  echo "NOTE: dexdump unavailable - NARROWED check: exact descriptor string presence only (not a class-definition proof)"
  for CLS in 'Lcom/cyclecare/app/rust/CycleCareCoreBridge;' 'Lcom/cyclecare/app/data/local/rust/RustTrackNative;'; do
    grep -lqF "$CLS" "$DEXDIR"/classes*.dex 2>/dev/null || fail "descriptor string $CLS absent from all DEX (narrowed check)"
    echo "OK (narrowed): descriptor string $CLS present"
  done
fi
echo "APK GATE PASS"
