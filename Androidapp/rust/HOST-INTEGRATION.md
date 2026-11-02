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

## Rust AppLockScreen host seam (v3 dist, connected behind RustAppLockConfig switch)

Host side (this branch, new files):
- `rust/CycleCareAppLockBridge.kt` - @JavascriptInterface registered as
  `window.cyclecareBridge`. Methods: `unlock(pin)`, `isBiometricEnabled()`,
  `triggerBiometric()`, `forgotCredentials()`. Each maps 1:1 onto the
  callbacks MainActivity already passes to the Compose AppLockScreen
  (viewModel.unlockApp, BiometricHelper.showBiometricPrompt,
  setShowAuthSheet(true)). No new auth path.
- `rust/AppLockWebViewHost.kt` - WebView factory: WebViewAssetLoader serves
  `app/src/main/assets/rust/applock/` (owner v3 dist, real logo PNG
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

## Connected wiring (spike switch, default OFF)

`rust/RustAppLockScreen.kt` + MainActivity: the isAppLocked branch now routes
through RustAppLockScreen. RustAppLockConfig.ENABLED=false keeps Compose as
the active lock UI; even when ENABLED, any WebView preparation failure
renders the Compose fallback. Both paths call the identical callbacks
(viewModel.unlockApp, BiometricHelper.showBiometricPrompt,
setShowAuthSheet(true)), so auth semantics do not depend on the rendering.

Dependency verification (executed, from the published artifacts):
- webkit-1.12.1.aar AndroidManifest.xml: uses-sdk android:minSdkVersion="21"
  -> below app minSdk 24, min24 constraint intact.
- webkit-1.12.1.pom compile deps: annotation 1.8.1, annotation-experimental
  1.4.1, core 1.1.0. All below the app's existing androidx versions; Gradle
  resolves upward, no downgrade.

## Lifecycle contracts (core v14 / core-ffi v5, integrated at spike aee5348+)

New FFI dispatcher contracts: startup_gate (9 booleans -> 5 gate flags),
effective_user_id (userId, deviceId -> id string), ensure_setup_ok (body ->
bool), has_remote_profile (httpOk, body, owner -> true/false/null),
verified_profile_parse (owner, econBody, profBody, nowIso -> 16-field profile),
reconcile_offline_claim (httpOk, body, balance, earned, rank, pendingDate ->
outcome APPLIED/ALREADY_RECORDED/REFETCH/OFFLINE_KEPT + fields).
resolve_access_token remains core-only (session_token.rs): needs a
transport/session adapter, not exposed over FFI yet.

Host wiring: CycleCareCoreBridge gains typed wrappers (StartupGateInputs/
StartupGate, RemoteProfileAnswer tri-state, raw-JSONObject passthroughs for
verified_profile_parse and reconcile_offline_claim). All wrappers return null
on native unavailability so the Kotlin path stays authoritative. NOTHING in
the production flow calls them; all are pure decisions over caller-supplied
snapshots - no local/account/wallet writes.

Integration note: core-ffi v5's Cargo.toml shipped path "../cyclecare-core"
again (wrong even inside its own tarball layout, where the sibling crate is
"core"); re-fixed to "../core" on the branch. Owner notified.

## Isolated call-site adapters (spike, @2cb4558+)

RustLifecycleAdapters.kt (com.cyclecare.app.rust) owns the guarded reopen ->
onboarding -> wallet slice for an existing owner:
- startupGate(auth, profilePresent, settingsOnboardingCompleted): native gate
  with Kotlin fallback reproducing ViewModel init:334-340 verbatim.
- effectiveUserId(auth): native with AuthPreferences fallback.
- verifiedProfileParse / reconcileOfflineClaim: parse/decision passthroughs
  over caller-fetched bodies; null fallback keeps the Kotlin parsers. The
  transport (OkHttp) stays in SupabaseEconomyService.
- SessionSnapshot + sessionSnapshot(auth): AuthPreferences-backed store the
  session_token resolver will consume once resolve_access_token is exposed.
Every adapter captures effectiveUserId + sessionGeneration and runs native
inside withIdentitySnapshot; a stale snapshot silently uses the Kotlin path.
Nothing in production flow references this file yet.
Portable compile of CycleCareCoreBridge + RustLifecycleAdapters against
minimal stubs: clean (kotlinc 2.1.0, JDK17). Full-app compile remains open.

## Call-site wiring status (@af04874)

CycleCareViewModel.init (ui/viewmodel/CycleCareViewModel.kt:334-341, 398-399)
now invokes RustLifecycleAdapters.startupGate behind
RustLifecycleConfig.STARTUP_GATE_ENABLED (compile-time const, currently
FALSE). Disabled: gate is null, original expressions run unchanged. Enabled:
native startup_gate decides hasCompletedInitialAuth / needsCloudProfileCheck /
isLocked / showOnboarding / fetchWallet; native unavailable or stale identity
snapshot -> Kotlin fallback with identical expressions. fetchWallet drives the
init-time economyService.fetchVerifiedProfile(initOwner) call. MainActivity
consumes the results via uiState (hasCompletedInitialAuth :179, isAppLocked
:210, showOnboarding :251) - no MainActivity edit needed. Wallet parse and
reconcile adapters remain ready-but-unwired pending this slice's proof.

## Wallet call-site wiring (@post-82974e6)

SupabaseEconomyService.fetchVerifiedProfile: after both server bodies are
fetched and owner/status-validated, native verified_profile_parse (behind
RustLifecycleConfig.WALLET_PARSE_ENABLED=false) produces the merged profile;
null (disabled/unavailable/native rejection or missing load-bearing fields)
runs the original Kotlin merge verbatim. Commit still goes through
withCurrentSession(owner, session) unchanged.
SupabaseEconomyService.reconcilePendingOfflineClaims: native
reconcile_offline_claim decision (behind RECONCILE_DECISION_ENABLED=false)
over the captured response body; ALREADY_RECORDED/REFETCH/OFFLINE_KEPT act on
the native outcome, APPLIED shares the single Kotlin apply path on the same
captured body; native unavailable falls through to the unchanged Kotlin path
(the captured body is reused, never double-consumed).
All switches ship false; enabling requires device proof on the exact HEAD.
