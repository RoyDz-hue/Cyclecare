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

## Memory settings (verified low-RAM recipe included)

The repo default `org.gradle.jvmargs` (-Xmx1536m, 768m metaspace) exceeds small (2 GB) build
containers and kills the Gradle daemon at startup.

**Recovered from the session that produced the successful minified a7e64fb build** (BUILD
SUCCESSFUL in 1h 6m 43s, delivered 2026-10-23 00:44 EAT, on a 2 GB container): that evening's
build scripts used `--no-daemon --max-workers=1` with SerialGC and low heap - two recorded
variants were 850m heap / 256m metaspace and 1100m heap / 320m metaspace. The exact flags of the
final successful launch are not recorded, so treat these as the recovered pattern, not a proven
exact recipe:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx850m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC' \
  assembleSlim -PslimApk
```

SerialGC + a single worker + low heap keeps the build inside 2 GB at the cost of speed - expect
about an hour for a cold build. The environment must stay alive for the full build; several
2026-10-23 attempts were killed by environment resets mid-build (reset cause unknown). Do not run
two Gradle builds concurrently.

Failed settings observed 2026-10-23 (all with the default G1 GC): repo defaults (daemon OOM at
startup); 700m heap / 320m metaspace (R8 heap OOM after ~26 min); 1350m heap / 320m metaspace
(KSP metaspace exhaustion during Kotlin compile). G1 needs more headroom than SerialGC at these
heap sizes. On machines with 4 GB+ RAM the repo defaults are fine and much faster.

## Fallback: memory-constrained diagnostic build (NEVER deliver)

If R8 cannot run at all, `-PnoMinify` skips minification and resource shrinking:

```bash
./gradlew assembleSlim -PslimApk -PnoMinify
```

The output is NOT obfuscated. Obfuscation is a hard delivery requirement, so a `-PnoMinify` APK
is an internal diagnostic artifact only: it must never be delivered to users or members. If only
this route is available, the correct outcome is "no deliverable APK", not a non-obfuscated one.

## Signing (never commit secrets)

- Keystore (keystore bytes + store password + alias `cyclecare-debug`) is held by the owner
  outside the repo. Never commit it. `.gitignore`
  enforces this: `.private/` and `*.jks` are excluded, so signing material cannot be committed or
  bundled into any APK (debug, release, or slim).
- Local: place the keystore at `Androidapp/.private/cyclecare-debug.jks` (or set
  `CYCLECARE_DEBUG_KEYSTORE`, `CYCLECARE_DEBUG_STORE_PASSWORD`, `CYCLECARE_DEBUG_KEY_PASSWORD`).
  The slim variant signs automatically when the keystore file exists.
- Other builders never need the keystore. Every builder produces an UNSIGNED
  APK (`-PslimApk` with no keystore present leaves it unsigned automatically) and hands it to the
  owner; only the owner signs. Sign an unsigned build
  with build-tools 34.0.0:
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

## In-place updates

An APK updates in place over an existing install only when ALL of these hold: same applicationId,
same signing certificate, and a higher `versionCode`. The signing key alone does not guarantee an
update path - keep the slim variant and the fixed key for user installs, and confirm the built
APK's applicationId matches the installed app (`aapt dump badging app.apk | grep package`).
The private key never leaves the owner's custody; the public certificate fingerprint below is safe to share.

## This guide is never shipped

This file (and BUILD-RECOVERY.md) lives at the repository root. Gradle only packages what is under
`Androidapp/app/src/` (code, resources, manifest), so repo-root docs are never bundled into any
APK variant. Do not move documentation into the app module or its `assets`.

## CI route

`.github/workflows/build-slim-apk.yml` (manual dispatch) builds the unsigned slim APK on
ubuntu-latest. Observed 2026-10-23: Actions on this account fails every run with
`startup_failure` (zero jobs), so verify Actions works before relying on it. See
BUILD-RECOVERY.md for the full incident record and current delivery state.
