# H1 local source snapshot, not runtime approval

Base c52af723f58793e3e6cf3ba16efcde52d12384d1. Uncommitted local additions only. No build service, device, actual key creation, database open, branch push or production edit performed.

## Harness route

New Java LabEncryptedHostInstrumentedTest uses explicit class filter and calls only LabEncryptedHostProof.run(Context,String,String):String. No Activity or MainActivity launch. No Kotlin/Room/coroutine ABI crosses test-to-app boundary. Facade itself lives src/lab, inherited lab keep retains facade. Runtime call is not executed locally. Package hard check precedes context effects; fresh generated UUID per owner, no identity from user state. Full corpus assets pinned from V11 outputs: 34 Track entries (33 upsert + delete), 8 settings/profile cases. Same A store across corpus with actual revision bases, so cases are no longer independent zero-state fixtures.

Facade directly calls actual LabEncryptedStoreFactory, mapper, validator, commit/withTransaction, inspect/DAO reads, helper reopen and preopened router. Instrumentation isn't an unused adapter classload test.

## Implemented schedules for later runtime

- Actual factory rejects wrong package and production filename. No default production alias/path fallback.
- All Track entries: 4/7 mutation-stage injected faults, independent DAO transaction digest equality after each rollback, commit, receipt replay, exact domain payload and revision checks. 8 settings/profile cases: same faults, exact payload/revision, validateOpen.
- Codec noncanonical double, nonfinite bits, signedzero contradiction rejection.
- Postcommit Unknown, explicit close APIs/idempotent close, new factory unwrap/reopen same owned file+alias, exact typed snapshot hash and same-operation receipt recovery, different-intent reuse rejection, stale base conflict.
- Retained secret helper-close/reopen while adapter alive. Secret byte contents never inspected in runtime harness.
- Missing envelope and bad ciphertext fail with unchanged DB file SHA256, restore owned envelope in finally without logging it. Actual missing/invalidation of Android wrapping key is not injected in H1, deferred because it needs irreversible test-key invalidation/restoration design. No wrong key fallback.
- Concurrent settings-base race: exactly one committed/one conflict.
- Actual A/B router, controlled deferred pause after B validation permits A commit before publication. Cross-owner then same-owner ABA; mismatched target full identity rejects; both namespace hashes unchanged on stale writes. Corrupt B receipt rejects publication, A remains writable. No UI is launched or painted. Actual stale projection paint tests remain separately scoped, not inferred from router success.

## Diagnostics and cleanup

Lab-only added snapshot methods read typed rows/revisions/journal/receipt/metadata inside independent withTransaction; deterministically sorted JSON and SHA256. No SQLCipher secret/passphrase/envelope returned. Failure writes exception class only, never text/stack/cause. Private evidence filename uses invocation UUID under lab filesDir, not logcat or network.

Before resource creation, registry ledger tracks generated config whose DB/prefs/alias preflighted absent. Success closes handles, validates exact factory registry binding, deletes only each owned database plus -wal/-shm/-journal, owned prefs and alias, and own registry entries. Leaves directory/shared registry and sanitized evidence. No wildcard directory deletion. Failure before cleanup preserves fixtures/envelope/alias and typed evidence already captured. Writes complete typed evidence before cleanup. A failure during cleanup can leave partial cleanup and reports cleanupStarted=true/resourcesPreserved=false. Pre-cleanup failures preserve owned fixtures; cleanup itself is not atomic. No promise of preserving deleted resources on a cleanup failure.

PackageManager audits permissions/providers/worker components before effects; ActivityManager audits running worker services before and after corpus. This does not see all in-process objects or threads. Manifest no-network permission + no WorkManager components plus no worker imports/calls in facade are bounded checks, not universal no-network/no-worker proof. No new Android Application class or initializer is installed. Runner initializes application before test; exact new merged manifest/startup needs rechecking on any later build.

## Executable gap classification

Exact Stage A 47 diagnostic gaps classified with actual DEX instruction sites: 27 metadata-only types, 3 API-dependent PixelCopy instruction types, 17 executable/helper types in test capture/future paths. Script attaches each site's class/method/opcode. Conservative class graph from unchanged runner + AndroidJUnit4 visits 746 test classes, reaches no instruction site for these 47 gaps. New Java test has only Android/JDK IO + app facade call, no screenshot/capture/future/coroutine calls in test DEX. New app facade coroutine/Room/SQLCipher paths stay app-internal. This is source boundary reasoning, NOT an optimized new artifact member/closure pass. Run exact new artifact required-def/root/both-order/callsite reachability review before any device. Platform36 local type-only run reports44 rather than CI47 because PixelCopy types exist in local platform jar; no suppressions added.

## Local validation

- Kotlin compile of entire lab store/facade plus exact Stage A generated KSP files against cached Android36, Room2.7.0, SQLCipher4.19.1 jars succeeds, no warnings.
- New Java instrumentation compiles against cached real runner/monitor/ext-junit APIs. Only javac source11 system-module-path warning.
- 57 prior actual pure validator/secret/router mock checks pass.
- 144 new local corpus mapping/payload/hash/typed snapshot/deferred-router mock checks pass. No facade run, Android Context, key or DB open executed.
- Full prior regressions rerun: 169 coordinator, 725 Track, 87 unified, 262 role predictions, 33 full-field codec/anchor +28 settings outputs, 12 care, 8 settings/profile. Expected astralUTF16 divergence remains pinned; production native parser disabled.
- Source/effect envelope updated explicitly: 250 files/2217 matching inventory rows. Initial drift failure occurred as designed after new source addition; regenerated inventory captures exact new methods. This is reviewed candidate, not silent proof of source safety. All 124 pinned production hashes still match. New schema remains synthetic v1; no production schema compatibility.

## Open gates

No new binary exists. Do not submit device/matrix, create keys, open DB or retry build under this source snapshot. Parent review must settle cleanup partial-failure semantics and any missing runtime/key-invalidation/projection requirements, then authorize next exact stage independently.
