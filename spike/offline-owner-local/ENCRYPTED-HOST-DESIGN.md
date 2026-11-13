# Encrypted host boundary, DESIGN ONLY, current a4d9a8d sources

No Android implementation/build/device/production access is authorized by this document. JSON fixture result is not encrypted proof. Below maps requirements to current Android effects and separately identifies missing work.

## Invariants to actual host boundaries

| Invariant | Current source/effect | Proposed required boundary; evidence still missing |
|---|---|---|
| Pinned owner/DB/effective ID/session/consent/epoch/mode | CycleRepository.beginOperation134, StoredContext/current/publishContext162; AuthPreferences.operationSnapshot278, effectiveUserId550; CycleCareDatabase.namespaceFor120 and getDatabase100 | Capture one identity from coordinated prefs+resolved opaque handle. Null guest DB is legacy cycle_care_database, journal owner legacy_guest, effective ID derives UUID(deviceId): distinct fields, never rename or conflate. Token emitted only after successful open/schema validation and same-owner/session recheck under shared transition coordinator. |
| Encrypted open, non-exported secrets | DatabaseKeyManager.getOrCreatePassphrase19 unwraps AES-GCM with fixed alias cyclecare_room_db_wrapping_key_v1, prefs cyclecare_local_db_key; CycleCareDatabase.buildDatabase around160 loads sqlcipher, SupportOpenHelperFactory | Real Android Keystore/JCE+Room/SQLCipher remain opaque host effects intermediate. Never send passphrase/wrapping key through JSON/Rust/logs. Existing manager constants are production-global: synthetic adapter requires separately injected test-only alias/prefs/filename, cannot call production defaults. Not implemented. Host SQLCipher library alone cannot prove Android key behavior. |
| Validated open before publish | Room @Database version9 exportSchema=false, entities seven types; migrations4->5->6->7->8->9; lazy build returns Room object | Force actual encrypted open and inspect actual Room identity/schema/expected tables+migrations+integrity before emitting handle/token, not successful builder return. Freeze old active context on wrongkey/corrupt/invalidatedkey/migration failure. No destructive fallback/create-new-on-error, no accidental guest fallback. Current source does NOT prove this complete protocol. |
| Conversion safety | DatabaseEncryption.ensureEncrypted uses .encrypted.tmp/.plaintext.backup, SQLCipher export/checkpoint/promotion, verification and sidecar removal | Conversion is a distinct destructive lifecycle boundary and excluded from first fresh synthetic test. Do not run existing conversion against live file. Threat review required: readable path removes sidecars, predictable tmp/backup and filesystem races. Prove no active handles/writers before any conversion; preserve original on failure. |
| Atomic local mutation | CycleDayLogDao inserts/deletes/settings; HealthSyncLocalDao revision/outbox; repository saveLog521/delete539 and updateSettingsBound387 | One Room withTransaction for full typed domain mutations, authoritative per-record revision+settings/profile base checks, journal and durable receipt. Current saveLog split captures and separate Track anchor settings commits are gaps, not already repaired. All write entrypoints/setters/workers must route through same coordinator. |
| Durable receipt | Existing health_sync_outbox PK(ownerId,operationId), server receipt fields; no dedicated local commit receipt entity in version9 | Add reviewed dedicated local receipt schema/migration; outbox may be consumed so not durable idempotency authority. Key owner namespace+opID, canonical intent digest+final record revisions/outcome. No guessed Room entity/schema digest. Schema migration is future work, not performed. SameID+sameDigest returns receipt; differentDigest rejects. Unknown reads sameID in original captured namespace before retry decision. |
| Consent/session publication ordering | AuthPreferences locks prefs; isLocalOnlyMode130 currently no shared lock; userId384/clearSession612/cloud138/generation163; OwnerDataGate.markWiped | Shared coordinator -> short prefs lock release -> Room transaction. No suspend under Java monitor. Consent transitions serialized or STOP. Existing prefs->repository publish order must be redesigned with all-path lock audit, not nested into new reverse order. Network/WorkManager outside coordinator. Synthetic consent generation input is not permission to mutate consent. |
| Snapshot/projection publication | repository captured snapshot/Flow, VM independent selected/today calculations, lab requireNative grid-map path | Snapshot after committed receipt under same identity, token/settings/anchor/request roles captured, Rust computes one exact grid+selected+today set. Publish only matching current identity/request; close Flow/reactive monitor bypass. UI success after commit, not plan. Actual crossmonth selected-card pixels not yet proven. |

## Seven fault points, exact proposed Room transaction placement

1. AFTER domain log insert/delete (before anchor).
2. AFTER conditional settings anchor write (only anchored Track operation).
3. AFTER log revision row insert.
4. AFTER log local outbox insert.
5. AFTER settings revision row insert.
6. AFTER settings local outbox insert.
7. AFTER dedicated local receipt insert, BEFORE transaction returns/commit.

All seven must rollback the entire Room transaction with injected error, verified by fresh DAO read in a new transaction and independent namespace digests. No filesystem JSON step is credited. Nonanchor/delete has domain/revision/journal/receipt four points. Settings+profile uses domain/settingsrevision/settingsjournal/profile/profilerevision/profilejournal/receipt seven points. Inject AFTER actual transaction commit but BEFORE receipt delivery separately: caller sees Unknown, original namespace durable receipt must recover outcome fromFILE via real DB query, no blind UUID replacement/reproposal.

Room cancellation before commit must rollback; cancellation/error with uncertain commit result stays Unknown until receipt query resolves. Receipt retention policy cannot discard unresolved IDs. Overflow/conflict checked against transaction-time rows. Different hash with same ID rejects even after restart. Domain row canonical codec/hash, float32 bits and signedzero readback need actual SQLCipher oracle; Java codec parity does not decide storage normalization.

## External writers, processes and lifecycle deltas

Current checked-in main manifest has no explicit android:process, but dependency-merged manifest/providers/workers and actual process list still require inspection. Process-local mutex does not serialize another process, worker with separate coordinator, direct DAO writer or multiple app instances. Audit every repository/worker/consent/session/lifecycle writer from inventory; fail closed on additions. For single process enforce singleton coordinator and inaccessible raw DAO write API. For multiple processes either explicitly prohibit second writers and prove it, or implement durable DB generation/receipt CAS plus OS/process coordination; not solved by file rename or Room transaction alone because prefs are separate storage.

DB provider monitor/Room/prefs must never wait on coordinator while held. Open resource preflight outside coordinator, publication recheck within; transitions stop new commits/drain leases before close/delete. Production key/DB never touched. Synthetic namespace keys must not alias production defaults. Tests need no sync worker/community ping/transport instantiation. New external writer evidence stops execution, not an excuse to weaken owner invariant.

## Genuine P1/P2 evidence plan, not yet executed

Fresh test-only encrypted namespace+alias, synthetic records only. P1 PID/start marker, exact APK/source/native/schema evidence. Commit barriers before each fault point and after COMMIT-before-delivery. Harness confirms P1 termination/PID gone (not activity recreation or close handle); relaunch P2 with new PID/start ID, same synthetic file+alias. P2 actual unwrap/open/schema verification -> receipt query -> row/revision/journal/settings/projection assertions. Capture logs without keys or raw health data, only synthetic fixtures.

Cases: kill before commit -> no receipt/no partial rows; kill after commit-before-delivery -> receipt and all rows recover; outstanding proposal alone -> no persisted health change; owner transition waiting/ABA -> old work rejected; wrongkey/alias invalidation -> no replacement/reset, existing digests unchanged. Verify unrelated owner namespace+production sentinel digests untouched, no network effects/flags. Process crash test and power-loss/fsync guarantees distinct; SQLite journal/WAL settings and device behavior need evidence. One passing process test does not prove all device/security versions.

## Migration debt ledger

- Kotlin OnboardingProfile default approximatePeriodChoice EXACT vs Rust empty: fixture supplies actual input, default mismatch remains.
- care_policy f64 vs Room/sync_codec f32: do not silently convert entity schema; known f32 fixture only.
- UTF16 take45 fallback vs Rust scalar take45: pinned expected mismatch; native parser disabled, no universal parity.
- App-lock/PIN/biometric/lifecycle policy not ported by codec app-lock omission; settings wire intentionally omits local-only security fields. Android secure UI/auth remains separate acceptance.
- Crossmonth grid+selected+today pure262 role parity, not drawn final selected card; epoch production wiring and actual pixels remain open.
- Real encrypted schema/migrations/durable local receipt entity and coordinator all-writer routing not implemented.
- Production Rust flags remain false; remaining UI/transport/auth/lifecycle/release scope still required for whole Android-to-Rust migration.

No new user permission rule implied. Existing migration/free synthetic scope must be evaluated by main before any later external stage; this document cannot approve it.
