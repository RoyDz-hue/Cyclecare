# Building the efficient CycleCare APK

For any coding assistant (or human) building this app: follow this guide to produce the small
APK instead of a bloated one. Size evidence: a measured slim arm64 build was 12.5 MB (delivered
2026-10-23 00:44 EAT, commit a7e64fb era). The project owner recalls ~7-11 MB for these efficient
builds versus ~37-40 MB from other agents' universal builds; treat those two figures as the
owner's recollection, with 12.5 MB the measured data point.

## The one command

```bash
cd Androidapp
./gradlew assembleSlim -PslimApk
```

Output APK: `Androidapp/app/build/outputs/apk/slim/arm64-v8a/app-slim-arm64-v8a.apk` (unsigned builds are named `app-slim-arm64-v8a-unsigned.apk`).

## Why this build is small

1. **arm64-v8a only.** `-PslimApk` enables the ABI split (`splits.abi` in `app/build.gradle.kts`),
   so the APK contains native code for 64-bit ARM only. Compatibility boundary: devices on 32-bit
   ARM (armeabi-v7a) or x86/x86_64 cannot install this artifact.
2. **R8 full mode + resource shrinking.** The `slim` build type sets `isMinifyEnabled = true` and
   `isShrinkResources = true` with `proguard-android-optimize.txt`. Dead code and unused resources
   are stripped.
3. **Debug-signed but minified.** `slim` inherits the debug variant's configuration but signs with
   the fixed debug key, so it installs in place over previous slim builds on a user's phone.
   Do NOT ship the plain `debug` variant: it has `applicationIdSuffix = ".debug"` (installs as a
   separate app) and uses a random per-machine debug keystore (updates will not install in place).

## Memory settings (tested 2026-10-23, read carefully)

The repo default `org.gradle.jvmargs` (-Xmx1536m, 768m metaspace) exceeds small (2 GB) build
containers and kills the Gradle daemon at startup. Tested failure/success ladder on a 2 GB
container:

- 1536m heap / 768m metaspace (repo default): daemon OOM, build never starts.
- 700m heap / 320m metaspace: reaches `minifySlimWithR8`, then R8 heap OOM after ~26 min.
- 1350m heap / 320m metaspace: KSP runs out of metaspace during Kotlin compile.
- 900m heap / 512m metaspace (non-minified `-PnoMinify` route): passed KSP and Kotlin compile and
  reached dex merging with no memory errors. That run was then killed by an environment reset, so
  end-to-end success at these settings is NOT verified.

None of today's (2026-10-23) attempts completed a minified build on a 2 GB container; earlier
minified builds did succeed on other environments. A machine with 4 GB+ RAM is recommended for the
minified route (recommendation, not a proven minimum). On constrained machines you may try
appending to `Androidapp/gradle.properties`:

```
org.gradle.jvmargs=-Xmx900m -XX:MaxMetaspaceSize=512m
kotlin.compiler.execution.strategy=in-process
```

Cold builds (no Gradle cache) took ~20-26 min to reach packaging; warm incremental builds are
much faster.

## Fallback: memory-constrained recovery build

If R8 cannot run at all, `-PnoMinify` skips minification and resource shrinking:

```bash
./gradlew assembleSlim -PslimApk -PnoMinify
```

Same features, same signing key, but a noticeably larger APK and no code obfuscation. Use only as
a delivery fallback; prefer the minified build whenever the machine allows it.

## Signing (never commit secrets)

- Keystore lives in the Instinct vault item "CycleCare fixed Android debug signing bundle"
  (keystore bytes + store password + alias `cyclecare-debug`). Never commit it; `.private/` is
  gitignored.
- Local: place the keystore at `Androidapp/.private/cyclecare-debug.jks` (or set
  `CYCLECARE_DEBUG_KEYSTORE`, `CYCLECARE_DEBUG_STORE_PASSWORD`, `CYCLECARE_DEBUG_KEY_PASSWORD`).
  The slim variant signs automatically when the keystore file exists.
- Unsigned builds (e.g. CI): sign afterwards with build-tools 34.0.0:
  ```bash
  apksigner sign --ks cyclecare-debug.jks --ks-key-alias cyclecare-debug \
    --ks-pass file:store_password.txt --key-pass file:store_password.txt \
    --out cyclecare-slim.apk app-slim-unsigned.apk
  ```

## Always verify before shipping

```bash
apksigner verify --print-certs cyclecare-slim.apk
```

Signer #1 cert SHA-256 must be:
`23:65:F8:88:A3:37:38:A1:B6:A1:EC:E0:3E:E7:D3:2E:8D:57:54:7C:FA:4F:1C:61:79:0B:35:14:97:F7:C0:CF`
A different certificate means the APK will NOT install over the existing app.

A matching certificate alone does not prove the APK is functional or lossless. Before shipping,
verify on a real arm64 device:
- Installs in place over the previous install (same package, versionCode higher, cert above).
- App launches; home, chat, and wallet screens render without missing resources.
- Google sign-in works with the repo's configured auth (no altered OAuth/client IDs).
- Account routing: returning user, new user, and damaged-data recovery each land on the right
  screen without bypassing consent gates.
- Wallet and realtime updates load and refresh.
- Chat: posting a message triggers the server reply path (post-post pulse ping).
- Payments stay fail-closed: no test charge paths against real wallets.

## CI route

`.github/workflows/build-slim-apk.yml` (manual dispatch) builds the unsigned slim APK on
ubuntu-latest. Observed 2026-10-23: Actions on this account fails every run with
`startup_failure` (zero jobs), so verify Actions works before relying on it. See
BUILD-RECOVERY.md for the full incident record and current delivery state.
