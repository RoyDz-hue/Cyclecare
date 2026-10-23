# Building the efficient CycleCare APK

For any coding assistant (or human) building this app: follow this guide to produce the small,
fully functional APK instead of a bloated one. Previously observed sizes: ~7-11 MB with this
route versus ~37-40 MB for naive/universal builds.

## The one command

```bash
cd Androidapp
./gradlew assembleSlim -PslimApk
```

Output: `Androidapp/app/build/outputs/apk/slim/arm64-v8a/`

## Why this build is small and fast

1. **arm64-v8a only.** `-PslimApk` enables the ABI split (`splits.abi` in `app/build.gradle.kts`),
   dropping x86/x86_64/armeabi-v7a native code. Virtually all physical phones are arm64; x86 is
   only emulators. This alone roughly halves the size versus a universal APK.
2. **R8 full mode + resource shrinking.** The `slim` build type sets `isMinifyEnabled = true` and
   `isShrinkResources = true` with `proguard-android-optimize.txt`. Dead code and unused resources
   are stripped.
3. **Debug-signed but minified.** `slim` inherits the debug variant's configuration but signs with
   the fixed debug key, so it installs in place over previous slim builds on a user's phone.
   Do NOT ship the plain `debug` variant: it has `applicationIdSuffix = ".debug"` (installs as a
   separate app) and uses a random per-machine debug keystore (updates will not install in place).

## Memory settings that actually work

The repo default `org.gradle.jvmargs` (-Xmx1536m, 768m metaspace) exceeds small (2 GB) build
containers and kills the Gradle daemon. On constrained machines append to
`Androidapp/gradle.properties`:

```
org.gradle.jvmargs=-Xmx900m -XX:MaxMetaspaceSize=512m
kotlin.compiler.execution.strategy=in-process
```

Notes learned the hard way (2026-10-23):
- KSP needs metaspace: 320m is not enough and fails during Kotlin compile.
- R8 (`minifySlimWithR8`) is the heap peak; 700m heap is not enough, ~900m works for the
  non-minified path. For a full minified build prefer a machine with 4 GB+ RAM.
- Cold builds (no Gradle cache) take ~25-35 min; warm incremental builds are much faster.

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

## CI route

`.github/workflows/build-slim-apk.yml` (manual dispatch) builds the unsigned slim APK on
ubuntu-latest. Observed 2026-10-23: Actions on this account fails every run with
`startup_failure` (zero jobs), so verify Actions works before relying on it. See
BUILD-RECOVERY.md for the full incident record and current delivery state.
