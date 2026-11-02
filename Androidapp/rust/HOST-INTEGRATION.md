# CycleCare Rust core - Android host integration (transitional slice)

Status: TRANSITIONAL EVIDENCE on branch rust-spike-s3-data-parity. This proves
existing-Gradle-host + JNI + cyclecare-core integration on the real app
structure. It is NOT the promised running Rust-authored screen (that track is
owned by the migration worker) and NOT whole-migration completion.

## What exists

- `rust/core/` - cyclecare-core v10 (183 contract tests; pure, no UI/IO).
- `rust/core-ffi/` - the migration owner's cdylib dispatcher crate consumed
  by `app/src/main/java/com/cyclecare/app/rust/CycleCareCoreBridge.kt`.
  Envelope {"ok": ...} / {"error": ...}; panics caught at the boundary.
  Contracts: sanitize_coin_amount, is_complete_pin, parse_local_date_iso,
  validate_period_date_strings, decide_reconnect, decide_reconnect_trailing,
  evaluate_safety, compute_baseline, verified_coin_amount, merge_restrictions.
  Android cross-build UNPROVEN (host-only so far).
- Gradle packaging: `app/build.gradle.kts` adds jniLibs srcDir
  `rust/core-ffi/jniLibs` + `packageRustCore` (preBuild) that copies
  `target/<triple>/release/libcyclecare_core_ffi.so` to
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

## Dioxus wasm screen track (owner's; integration constraints found by review)

- Scaffold dist inspected (v1): no remote code - the only URLs are doc
  comments; the single fetch() is wasm-bindgen's local .wasm load.
- INTEGRATION REQUIREMENT: WebAssembly instantiation via fetch on a file://
  origin is blocked in WebView; the host must serve assets through
  WebViewAssetLoader (https://appassets.androidplatform.net origin) or the
  wasm init must use the ArrayBuffer (non-streaming) path. To be settled when
  the real screen's host Activity is wired.
- Host rules from main: local assets origin only, navigation allowlist, no
  remote JS, minSdk 24 preserved, signing + screen-capture/privacy settings
  preserved.
