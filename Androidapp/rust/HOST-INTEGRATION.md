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

## Rust AppLockScreen host seam (v2 dist, spike-only, not wired into MainActivity)

Host side (this branch, new files):
- `rust/CycleCareAppLockBridge.kt` - @JavascriptInterface registered as
  `window.cyclecareBridge`. Methods: `unlock(pin)`, `isBiometricEnabled()`,
  `triggerBiometric()`, `forgotCredentials()`. Each maps 1:1 onto the
  callbacks MainActivity already passes to the Compose AppLockScreen
  (viewModel.unlockApp, BiometricHelper.showBiometricPrompt,
  setShowAuthSheet(true)). No new auth path.
- `rust/AppLockWebViewHost.kt` - WebView factory: WebViewAssetLoader serves
  `app/src/main/assets/rust/applock/` (owner v2 dist, real logo PNG
  byte-identical to drawable-nodpi, sha256 b8da177e...63f8) under origin
  https://appassets.androidplatform.net; navigation allowlist denies
  everything else; file/content access off; DOM storage off.
- Requires androidx.webkit:webkit 1.12.1 (added to catalog + app module).

unlock() vocabulary (agreed contract): single string return, lowercase -
"unlocked" (production onUnlock true), "rejected" (onUnlock false),
"unavailable" (technical failure; must NOT show the wrong-PIN copy and
must NOT count as an auth attempt).

Screen-side requirements still open with the owner (v2 source reviewed):
1. v2 JS still calls cyclecareBridge.unlock(pin) TWICE when the first
   result is falsy (`unlock(...) === true || unlock(...) === 'true'`) and
   compares against boolean true, which does not match the tri-state
   string contract above - an "unlocked" string would render as Rejected.
   Fix: call once, store the result, compare against the string
   vocabulary: 'unlocked' / 'rejected' / anything-else = unavailable.
2. PIN is interpolated raw into the JS string literal. Embed it as a JSON
   string (serde_json::to_string of the pin produces a valid, safely
   escaped JS string literal) instead of wrapping in single quotes.
3. Biometric + Forgot buttons render unconditionally with no onclick
   wiring. Production gates biometric on isFingerprintEnabled and forgot
   on the callback being present. Wire onclick to
   cyclecareBridge.triggerBiometric() / forgotCredentials() and gate the
   biometric button on cyclecareBridge.isBiometricEnabled().
4. New string introduced in v2: "Unlock is not available right now.
   Please try again in a moment." - not present in production; needed for
   the new UNAVAILABLE state, flagged for product visibility.
5. Auto-unlock fires at 4 JS chars; production counts UTF-16 units
   (length <= 4). Supplementary-plane input (emoji) differs - low impact,
   recorded, no fix required unless product cares.
