# Build recovery state (2026-10-23)

## What is deployed and live
- Community Guide humanisation: commits 54e63bc, 9aac311, 3f837b7, f8c414a. All server-side
  (Supabase `community-pulse` edge function + DB personas + pg_cron driver). Live now; no APK needed for these.
- Daily provider-attempt ceiling: 40/day, lazy reset at 00:00 UTC (03:00 EAT). Pauses with zero provider calls when exhausted.

## What is NOT yet delivered
- Android APK containing the post-post pulse ping (from 9aac311). Server works without it; the APK only shortens reply latency.

## Build routes and observed status
- Local builds on a 2GB RAM machine: minified slim build cannot run (R8 heap OOM; KSP needs >320m metaspace; combined exceeds 2GB).
  Non-minified route builds cleanly but the build environment reset mid-build 4 times (2026-10-23), so no artifact survived.
- GitHub Actions: `.github/workflows/build-slim-apk.yml` (workflow_dispatch) builds `assembleSlim -PslimApk` UNSIGNED.
  Observed blocked: runs fail instantly with `startup_failure`, zero jobs - even a one-line probe workflow
  (`.github/workflows/ci-probe.yml`) failed identically. Account-level Actions block, not a workflow problem.
- Codespaces: plan `free`, 0 existing codespaces, machines endpoint lists basicLinux32gb (2-core) and standardLinux32gb (4-core);
  zero billable usage recorded Oct 2026. Whether free monthly quota applies/remaining is NOT visible via API - creation
  would consume the account's Codespaces allowance and needs the owner's explicit approval first.

## How to build the slim APK (any machine with 4GB+ RAM)
1. JDK 17 + Android SDK (platforms;android-34, build-tools;34.0.0).
2. `cd Androidapp && ./gradlew assembleSlim -PslimApk` (arm64 only, minified).
   On memory-constrained machines add `-PnoMinify` (larger APK, no obfuscation/resource shrinking, same features).
3. Signing: when `.private/cyclecare-debug.jks` (or `$CYCLECARE_DEBUG_KEYSTORE`) exists, the slim variant signs with the
   fixed debug key automatically. CI/unsigned output can be signed after the fact:
   `apksigner sign --ks cyclecare-debug.jks --ks-key-alias cyclecare-debug --ks-pass file:store_password.txt --key-pass file:store_password.txt --out signed.apk app-slim-unsigned.apk`
4. Verify: `apksigner verify --print-certs signed.apk` - cert SHA-256 must be
   `23:65:F8:88:A3:37:38:A1:B6:A1:EC:E0:3E:E7:D3:2E:8D:57:54:7C:FA:4F:1C:61:79:0B:35:14:97:F7:C0:CF`
   (matches the currently installed app; updates install in place).

Keystore and passwords are held by the owner outside the repo. Never commit them.

## Build guide

ANDROID-BUILD-GUIDE.md is the canonical guide for producing the small (~7-11 MB) efficient APK,
including memory settings, signing, and verification. Read it before any build attempt.
