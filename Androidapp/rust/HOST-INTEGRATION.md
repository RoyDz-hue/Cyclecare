# CycleCare Rust core - Android host integration (transitional slice)

Status: TRANSITIONAL EVIDENCE on branch rust-spike-s3-data-parity. This proves
existing-Gradle-host + JNI + cyclecare-core integration on the real app
structure. It is NOT the promised running Rust-authored screen (that track is
owned by the migration worker) and NOT whole-migration completion.

## What exists

- `rust/core/` - cyclecare-core v10 (183 contract tests; pure, no UI/IO).
- `rust/core-jni/` - cdylib crate exporting JNI symbols consumed by
  `app/src/main/java/com/cyclecare/app/rust/CycleCareCoreBridge.kt`.
  COMPILE-UNVERIFIED in the ops sandbox (no Rust toolchain); first compile
  happens in a bounded toolchain run.
- Gradle packaging: `app/build.gradle.kts` adds jniLibs srcDir
  `rust/core-jni/jniLibs` + `packageRustCore` (preBuild) that copies
  `target/<triple>/release/libcyclecare_core_jni.so` to
  `jniLibs/<abi>/libcyclecare_core.so`. Missing .so = silent skip, Kotlin
  fallback keeps the app fully functional.
- Proof path: Settings quick-unlock PIN validation (identity-free) routes
  through the bridge with automatic Kotlin fallback.

## Interface contract (for the Rust-screen track)

- Library name: `System.loadLibrary("cyclecare_core")` -> file
  `libcyclecare_core.so` (renamed copy of the cdylib output
  `libcyclecare_core_jni.so`).
- JNI symbol rule: `Java_com_cyclecare_app_rust_CycleCareCoreBridge_<method>`;
  no underscores in identifiers, so no `_1` mangling needed.
- ABI map: aarch64-linux-android -> arm64-v8a; armv7-linux-androideabi ->
  armeabi-v7a; i686-linux-android -> x86; x86_64-linux-android -> x86_64.
  minSdk stays 24 (no raise without Roy's decision via main).
- Exports are PURE: no globals, no identity state. Identity-dependent
  contracts must go through CycleCareCoreBridge.withIdentitySnapshot (owner +
  sessionGeneration re-checked after the native call; stale -> Kotlin
  fallback), mirroring CycleCareViewModel's stillOwns pattern.
- Every native contract requires a Kotlin fallback; the app must never depend
  on the .so being present.
- Runtime handshake: nativeCoreHandshake() == 42 before any contract is used.

## Open items carried from core review

- verified_coin_amount integral-double text ("363.0" vs "363") - executed
  divergence, unfixed.
- roundToInt tie behavior - pending verbatim probe (RoundProbe.kt forwarded).
- Android wrapper (sqlcipher-android) readback of a Rust-written DB - unproven.
- Dioxus/wry host integration - unverified (migration worker's track).
