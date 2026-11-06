# CycleCare Rust integration: disabled diagnostic candidate

Not committed, not an accepted release, not a completed full Rust migration.

## Proposed composition

- Base Rust spike2b684073039f135c8f5e1553b64117cbba95b8e2.
- Separate hardening source candidate da0c53711e513991468e478fcc7b96a358d66fa9
  (includes D/Efb670dfa and priorV8). Diagnostic only, not accepted protection.
- baseline-v1 minimal caller.diff and RustBaseline.kt, config false.
- Existing native Android host/UX/Kotlin fallbacks preserved, flags stay false.
- Replace old checked-in binary only in a NEW reviewed isolated branch after
  current pinned diagnostic, never main/release. This package does not do it.

## Latest native artifact provenance

- Corev118 archive SHA256:
  e83fada64b001171d7e58e78402e8c12acd1862955753b9b8091f529d61c814f
- FFIv109 archive SHA256:
  4bdf8f70f53ca29f93233c6d261198aef68fb42f56299f8fb6bb5e1ae8f76ddd
- Android aarch64 API24 artifact,1363624bytes,SHA256:
  dcb37aa43dc2931be81de10090be3fdd2967bbc58f8285cef60ba21e6de48521
- Proposed path: rust/core-jni/jniLibs/arm64-v8a/libcyclecare_core.so.
- Owner previously cross-built supplied artifact with16KB ELF LOADalignment;
  this proposal re-hashes/inspects exports, not a fresh provenance rebuild.
- CI must verify both input and packaged native bytes. packageRustCore can
  overwrite the input from a cached target artifact, so final hash matters.
- JNI bridge class com.cyclecare.app.rust.CycleCareCoreBridge, methods
  nativeCoreHandshake/nativeCall,handshake42. Inspect actual R8 mapping/DEX
  for native name retention before accepting runtime. No Android proof yet.

## Evidence boundaries

Linux actual JNI host replay: calendar540months+invertedgates; baseline1200
canonical snapshots,all8metrics exact,zero nativefallback; invaliddatefallback
separately exercised. Selected input evidence,not full parity/Android proof.

V10 actualVM mockedhost regression: guest supersession,onboardingoldowner
and cleanup,explicit cancellation,failedlocalreadforeground,DBepoch switch
before/after sync. Not actual DAO/token protection. V9Roomatomicrollback
and constructor capture remain source-reviewed only; prefs-backed tokens,
consentcheck-to-commit,offlineclaimfollowthrough,encryptedRoom/Keystore,
pixels,TalkBack,Android launch and all-app Rust migration remain open.

## Proposed diagnostic build boundary

Free personalM2only,billingoff,45mincap,no retries,signing secrets or deploy.
Unsigned slim uses R8+resource shrinking. Preserve package/version/keycustody.
Inspect APK hash/nativehash/manifest/R8mapping/signing state before any use.
Success is packaging diagnostic only,not an installable finished Rust app.

## Corrected diagnostic acceptance contract

Codemagic UI success alone is NOT acceptance. verifier final script records
build-logs/verification-exit-code.txt and returns0 only to retain diagnostics.
Required acceptance: marker exists and is0, packaged-native report passes,
no v1 signatures or APKSigningBlock, apksigner report retained, exact JNI
DEX class/native methods and dynamic ELF exports pass, externally supplied
expected source SHA matches actual HEAD. Nonzero/missing marker = rejected
artifact, never described as verified unsigned or delivered to user.

Four actual hardening test files staged-compile against realRobolectric4.16.1
and AndroidXtestcore1.6.1,15classes,zero diagnostics. Tests are preserved, not
excluded. Not full Gradle/KSP/runtime execution. SDK34 provider4.16.1 source
uses Android14 build10818077 preinstrumented version7, matching prefetch
14-robolectric-10818077-i7. MirrorHEAD200 verified. Gradle mirror hook itself
still requires actual runner execution. No next CI launch authorized yet.

Correction to earlier shorthand: reviewer ls-tree reports no tracked .so at
base2b68407. Current pinned slim build does not cargo-build core-ffi; absent
tracked or produced .so it packages Kotlin-only, NOT an 'old native' APK.
The current APK must be inspected before any packaged-binary claim. The new
candidate must explicitly ADD the supplied .so, checked by git ls-files and
native hash. This transfer contains proposal source only, not that binary.

V3 acceptance: SDK path uses ANDROID_HOME or ANDROID_SDK_ROOT. Structural
native/unsigned report is emitted before tool lookup. Apksigner is a hard
acceptance gate: exit1 AND explicit DOES NOT VERIFY/Missing MANIFEST output
required in addition to structural no-signature proof. This is deliberately
conservative and unvalidated against actual runner output; unexpected tool
output means NOT_ACCEPTED, never downgraded to a warning or unsigned inference.
ACCEPTANCE.txt says ACCEPTED_PACKAGING_DIAGNOSTIC_ONLY or NOT_ACCEPTED.
This still does not mean Android runtime/full Rust migration acceptance.

Launch preflight must inspect normal Codemagic build modal and prove it can
supply expected_source_sha input. If UI does not expose it, do not launch.
Use an independently reviewed external public env/input route, never hardcode
a self-referential final commit into its own contents. ColdM2duration unknown;
45minute cap is a bound,not an estimate. Do not increase or retry automatically.
