#!/usr/bin/env bash
# Builds the core-ffi cdylib for one Android target (default aarch64) with the
# NDK clang linker at API 24 (minSdk). 16 KB page alignment comes from
# core-ffi/.cargo/config.toml (do not set RUSTFLAGS, it would override it).
# Usage: ANDROID_NDK_HOME=/path/to/ndk rust/build-android.sh [triple] <rust-pin>
# Output: core-ffi/target/<triple>/release/libcyclecare_core.so (picked up by
# the packageRustCore Gradle task). Verify: llvm-readelf -lW <so> LOAD Align
# 0x4000 on every row (verify-android-artifact.sh owns this gate).
#
# TOOLCHAIN PINNING: an installer --default-toolchain alone is overridable
# (reviewer). The exact pin is a REQUIRED argument; every compiler invocation
# runs under `rustup run <pin>` and the resolved rustc/cargo are attested
# (release + host) before building. No ambient/default toolchain is used.
set -euo pipefail
T="${1:-aarch64-linux-android}"
RUST_PIN="${2:?exact rust toolchain pin required as arg 2 (e.g. 1.99.1)}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
# Host-tag from the actual runner, not a hardcoded platform: the NDK ships
# per-host prebuilts (linux-x86_64 / darwin-x86_64). Fail closed if the
# runner's prebuilt dir is absent.
case "$(uname -s)" in
  Linux)  HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) echo "UNSUPPORTED host OS: $(uname -s)" >&2; exit 1 ;;
esac
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$BIN/llvm-ar" ] || { echo "NDK prebuilt tools missing at $BIN" >&2; exit 1; }

# Attest the resolved pinned toolchain BEFORE compiling: exact release and
# host triple must be visible, fail closed otherwise.
RUSTC_VV="$(rustup run "$RUST_PIN" rustc -vV)" || { echo "rustup cannot resolve pin $RUST_PIN" >&2; exit 1; }
echo "$RUSTC_VV"
echo "$RUSTC_VV" | grep -q "^release: $RUST_PIN\$" || { echo "rustc release is not exactly $RUST_PIN" >&2; exit 1; }
echo "$RUSTC_VV" | grep -q "^host: aarch64-apple-darwin\|^host: x86_64-unknown-linux-gnu" \
  || echo "note: non-macOS build host (host triple printed above)"
rustup run "$RUST_PIN" cargo -vV

case "$T" in armv7-linux-androideabi) CL="armv7a-linux-androideabi24-clang";; *) CL="$T"24-clang;; esac
UP=$(echo "$T" | tr 'a-z-' 'A-Z_')
export "CARGO_TARGET_${UP}_LINKER=$BIN/$CL"
export "CC_${T//-/_}=$BIN/$CL"
export "AR_${T//-/_}=$BIN/llvm-ar"
cd "$(dirname "$0")/core-ffi"
rustup run "$RUST_PIN" cargo build --release --locked --jobs 1 --target "$T"
