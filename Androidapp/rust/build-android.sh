#!/usr/bin/env bash
# Builds the core-ffi cdylib for one Android target (default aarch64) with the
# NDK clang linker at API 24 (minSdk). 16 KB page alignment comes from
# core-ffi/.cargo/config.toml (do not set RUSTFLAGS, it would override it).
# Usage: ANDROID_NDK_HOME=/path/to/ndk-r27c rust/build-android.sh [triple]
# Output: core-ffi/target/<triple>/release/libcyclecare_core.so (picked up by
# the packageRustCore Gradle task). Verify: llvm-readelf -lW <so> | grep LOAD
# must show Align 0x4000 on every LOAD row. No Gradle, no signing, no network
# beyond crate fetch.
set -euo pipefail
T="${1:-aarch64-linux-android}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
case "$T" in armv7-linux-androideabi) CL="armv7a-linux-androideabi24-clang";; *) CL="$T"24-clang;; esac
UP=$(echo "$T" | tr 'a-z-' 'A-Z_')
export "CARGO_TARGET_${UP}_LINKER=$BIN/$CL"
export "CC_${T//-/_}=$BIN/$CL"
export "AR_${T//-/_}=$BIN/llvm-ar"
cd "$(dirname "$0")/core-ffi"
cargo build --release --locked --jobs 1 --target "$T"
