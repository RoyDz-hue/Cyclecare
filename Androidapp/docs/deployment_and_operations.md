# Deployment and operations

## Production is unchanged

The historical approved receipt records migration `cyclecare_uuid_compatible_repairs_20261018` (applied version `20261018112033`), `ai-gateway` v12 and `image-generation` v5. Both AI functions retain JWT verification. That receipt is not a live health check or approval for later changes.

All subsequent changes are feature-branch source in `cue/ai-provider-alignment-20261018`. Android/backend and Admin PWA are separate repositories. A commit, debug APK or successful local test is not a production release. Do not merge, publish hosting, deploy functions, enable feature gates or change production secrets without separate approval.

## Forward SQL candidates, not replayable migration history

Do not run a blanket `supabase db push` or replay the old full-schema, transaction-schema or timestamped migration history against the existing project. The approved UUID repair is retained as a reference proposal; its filename differs from the applied production version.

The new candidates are:

- `supabase/proposals/20261019000100_payment_settlement_protocol.sql` — service-only intent/evidence/ledger accounting and reconciliation.
- `supabase/proposals/20261019090000_health_sync_v3.sql` — revision-safe sync and unified health erasure.
- `supabase/proposals/20261019143000_cyclecare_ai_processor_consent_runtime.sql` — processor manifests, policy eligibility, consent CAS and protected Admin runtime writes.

Test the complete set on a fresh disposable representative catalog. Before any rollout, compare the actual production catalog, grants, owners, function bodies and applied history; create a separately reviewed forward-only migration from these candidates; then obtain approval for that exact rollout. Already-applied history must not be rewritten.

## AI provider control and consent

Cohere, Gemini and OpenRouter are chat providers. Pollinations is image-only. The authenticated Admin RPC controls the server selection; Android does not choose a cloud provider or silently substitute one when configuration is missing.

The candidate gateway requires schema v3 and authenticated, owner-bound, revision-matching processor receipts. Android loads the reviewed server manifest, discloses processors and upstream routing, and grants only after an authoritative matching receipt. Withdrawal is immediate locally and remains pending until the server confirms it. Account/session snapshots and consent CAS prevent late replies or an old login from restoring withdrawn permission. Legacy non-CAS receipt mutation is not exposed.

Models, policy revisions and upstream details are pinned by the manifest. Candidate requests do not accept model environment overrides that would silently change the disclosed processor. OpenRouter pins its upstream, disables provider fallback and requests no collection and zero retention; those requested controls are not a promise of eligibility or actual provider retention. Account, audience and use-case policy records default to unreviewed.

Hybrid has an actual sequential runtime and database contract, not parallel speculative requests. It requires both disclosed processors' current receipts, matching reviewed manifests, current restriction checks, bounded I/O and per-attempt quota before any dispatch. It remains source-disabled, database-disabled and unavailable in the production-compatible Admin selector. Enabling a database/environment flag alone cannot release it. The candidate Admin RPC rejects a closed or unreviewed Hybrid selection.

Secret presence means configured, not authenticated or healthy. Prior Cohere/OpenRouter read-only key checks do not prove generation success. Gemini previously rejected its key; no replacement or successful generation is claimed. Production credentials stay in server-side Edge secrets, never Android, Vite, source or logs. A provider/account or model change requires a new reviewed manifest and fresh affected consent.

## Durable health sync and erasure

Android now has Room v8 migrations, transactional owner-bound mutation journaling, a persisted outbox, retry metadata, revisions, cursors, tombstones and conflict records. Local mutation sequences and server CAS revisions are separate: an old receipt cannot acknowledge a newer local edit, and rebasing cannot decrease local ordering. A reviewed local/server conflict decision is rejected when the account, session or reviewed record has changed. Settings offers bounded previews and explicit Keep mine / Use server actions; server tombstones cannot be resurrected by Keep mine.

The v3-only transport validates typed receipts, logical identities, server consent epochs, cursor ordering and bounded response reads. Tombstones carry logical identity without old health payloads. Background sync cannot grant consent or enroll historical records automatically. An explicit future opt-in may separately choose to journal existing data. Legacy v2 health upload/download is not a fallback.

Device-local lock settings, notification preferences, account mode and legal/age acceptance are not cloud health payloads. Remote restores and tombstones preserve those local choices and cannot certify legal acceptance or turn off the device lock.

The unified health-erasure transaction removes legacy logs, health-bearing profile fields, v3 records/operations and processor receipts under the shared owner lock. Local erasure also removes outbox, revision, cursor and conflict payloads. Only disabled monotonic security clocks remain so pre-erasure requests cannot become valid after a future explicit resumption. No historical health payload is kept in those clocks. Auth identity, financial/economy/audit records and the privacy deletion marker are distinct retained account records.

Health erasure is not full account deletion, merchant-record deletion or provider-side AI erasure. A retention/account-lifecycle policy still needs explicit approval. The runtime health-transfer gate remains false until reviewed staging and two-device acceptance; local tests do not authorize a production enablement.

## Payment reconciliation and economy accounting

The PayPal candidate now includes durable owner/package request IDs, persistent provider-action claims, fixed provider origins, exact minor-unit amount/currency checks, server-recorded order matching, independent read-only order/capture verification and signature-verified webhook evidence. Reconciliation never initiates a charge or refund. An ambiguous create/capture is not blindly resubmitted after a timeout or provider idempotency expiry.

Verified events and economy entries are immutable and replay-safe. Capture posts once; full refund/reversal removes purchased value once. Spent-value reversals create debt rather than negative spendable coins; subsequent credits repay that debt before becoming spendable. Purchased packages cannot farm permanent XP or retained paid entitlements through refund cycles. Same-reference identity conflicts, duplicate events, terminal-state conflicts and concurrent requests are tested transactionally. Partial or otherwise ambiguous provider evidence is quarantined, not guessed into success or a new credit.

`PAYMENT_PROTOCOL_RELEASED = false` is a hard source gate. PayHero initiation/callback, M-Pesa, OKX/PayNexus and direct card processing remain closed where independent settlement controls are unavailable. The old approved rollout did not deploy these local payment gates; do not assume production behavior changed. Only the signature-authenticated PayPal webhook omits platform JWT verification; user-facing payment and consent endpoints retain it and the webhook remains source-gated.

Tests use local fake provider responses, actual controllers and disposable PostgreSQL accounting/race fixtures. They are not PayPal-hosted sandbox acceptance, real charges, refunds, clinical approval or production settlement. Subscription renewals and expiry must not be inferred from coin-package metadata.

## Build and isolated verification

Android requires the configured JDK/SDK for API 36. Core-library desugaring preserves the declared API 24 minimum. Invalid or partial public backend configuration fails closed; it must not redirect requests into production. Only the public URL and public anon/publishable key belong in `.env.android`.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
node --test supabase/functions/_shared/*.test.mjs
# Separate sibling repository, not an Android subdirectory:
cd ../Cyclecare-admin
npm ci
npm test
npm run build
```

Database tests are for fresh disposable databases only. `health-sync-v3.sql` installs its representative fixture and all three candidates and asserts real public-wire behavior, erasure, permissions and consent epochs. The separate concurrency script requires `CYCLECARE_DISPOSABLE_DB=1` and `PGDATABASE`; use normal PG connection environment variables for the disposable server. `hybrid-consent-runtime.sql` runs after its fixture/candidate and rolls back its test-only review/membership grants. Payment SQL and concurrency fixtures exercise real accounting, not duplicated test-only arithmetic.

The Admin repository owns root `netlify.toml`, builds with `npm run build` and publishes `dist`. Building it is not publication. Retain session-refresh and provider-save concurrency tests. A valid access-token rotation is not a new login; logout/relogin must invalidate pending work.

## What still requires real approval or access

A qualified clinician must review escalation rules, fertility/pregnancy wording, intended users, supported languages and the matrix in [clinical acceptance](clinical_acceptance.md). English keyword checks can miss danger; no warning is not assurance of safety. Keep sign-off empty until the reviewer actually approves it.

Physical-device and two-device authenticated staging acceptance must cover SQLCipher/Keystore recovery, account switching, outbox restarts, conflicts, erasure/revocation, accessibility and real keyboard behavior. JVM preferences and temporary Room databases do not establish hardware encryption or physical-device acceptance. A debug APK and built instrumentation APK are not a signed store release.

Provider eligibility requires account/DPA, retention, audience and use-case evidence; Gemini additionally needs a valid eligible server-side credential. PayPal-hosted sandbox lifecycle acceptance needs sandbox merchant client ID/secret, webhook ID and buyer access plus approved return/cancel origins and handling policy for ambiguous/partial refunds. No live merchant credentials or real-payment authorization are implied.

Production release, protected signing-key access and any future paid calls require their own explicit authorization. Passing tests does not replace those approvals.

## Remaining-requirements inventory

Implemented source is not released functionality. The latest hardening makes unmatched symptom text explicitly assessment-limited, retains that warning in local clarification, persists withdrawal before cloud erasure, rejects stale session/processor/wallet replies and refuses to call a cached or offline wallet authoritative. A single explicit processor choice does not require granting other processors; it cannot authorize a server-selected processor without its current receipt. Closed checkout shows an unavailable notice before collecting any payment inputs.

| Requirement | Source status | What remains |
|---|---|---|
| Durable v3 health sync, conflicts and health erasure | Implemented; hard gate closed | Authenticated staging, two-device restart/conflict/withdrawal/erasure acceptance and reviewed enablement |
| Provider-specific consent and Admin-enforced selection | Implemented candidate contract | Reviewed eligible provider accounts/policies, staging integration and fresh affected consent |
| Sequential Hybrid | Implemented executor/database; source, runtime and Admin gates closed | Both processors' current approvals/receipts, pinned-upstream eligibility and staging acceptance before separate release approval |
| PayPal reconciliation and reversal/debt accounting | Implemented candidate; checkout closed | Hosted sandbox lifecycle and merchant-policy acceptance; mocks are not hosted acceptance |
| Clinical and language safety | Conservative fallback/regressions, no clinical approval | Named qualified clinician, intended population/jurisdictions, supported languages and escalation wording; no multilingual clinical capability is claimed |
| Physical-device acceptance | Offline harness/debug artifacts exist, not hardware-tested | Authorized online host/USB-debugging handset(s), staging accounts, Keystore/SQLCipher recovery, account switching, two-device sync, keyboard and accessibility checks |
| Gemini | Credential and eligibility unverified | Replacement eligible server-side key plus account, audience/age, use-case and data-handling review; a key alone cannot approve health-data processing |
| All external processors | Manifest/policy records fail closed while unreviewed | Account/DPA, retention, region, audience/use-case and upstream/model evidence; later changes require reviewed manifests and fresh consent |
| Hosted PayPal sandbox | Not performed | Sandbox merchant client ID/secret, webhook ID, buyer access, approved return/cancel origins, ambiguous/partial-refund policy and lifecycle evidence |
| Other payment rails | M-Pesa/PayHero, PayNexus/OKX and direct card remain unsupported/closed where settlement is incomplete | Verified contracts, independent settlement/reversal controls and isolated/hosted acceptance before support |
| Recurring subscriptions | Renewal/expiry lifecycle not implemented | Owner-approved entitlement, cancellation/refund and product contract before implementation; coin packages cannot grant renewals |
| Full account/provider-side deletion | Not implemented by health erasure | Owner/legal retention and lifecycle decisions, provider erasure mechanisms, safeguards and acceptance; disclose retained identity/financial/audit records |
| Forward migration and rollout | Candidates tested only on fresh disposable representative catalogs | Authorized target catalog/grant/owner/function/history comparison, exact forward-only migration review and separate staging/production approval; no blanket replay |
| Release/store publication | Debug artifacts only, no merge/deployment | Protected signing-key access, signed-release acceptance, distribution decisions and explicit rollout approval |
| Paid provider/live financial testing | Not authorized or performed | Separate explicit authorization; this task does not grant it |
| Lint maintenance | Safe style/catalog/icon corrections implemented; intentional advisories stay visible | Preserve synchronous durability and checked commit results; review pinned-version updates separately with API 24 and encryption/worker compatibility tests; see the lint review below |

No engineering test fills an approval checkbox. The payment race harness requires `CYCLECARE_DISPOSABLE_DB=1` and an explicitly disposable `PAYMENT_TEST_DSN`. Each run uses a fresh synthetic checkout reference and requires one creator plus one duplicate, so prior successful runs cannot mask a create race.

## Android lint review

The starting debug report contained 95 warnings and no errors. This maintenance pass changes no dependency version, SDK level, desugaring setting, schema or release gate. There is no lint baseline, disabled check, severity downgrade or warning suppression hiding the retained findings.

Final validation: **58 warnings and zero errors in both debug and release lint**, with **201 unit tests passing and no failures, errors or skips**. The debug APK, instrumentation APK and release Kotlin compilation also pass. The net reduction from the original report is 37 warnings; the migration-editor warning newly exposed on the first rerun was corrected too. The 58 retained findings are 14 explicit-editor style advisories, eight synchronous-write advisories and 36 pinned-version availability advisories. Instrumentation was built, not executed on a handset; no release APK was signed or published.

Safe corrections are limited to existing asynchronous preference edits using the already-pinned AndroidX KTX helper, the equivalent `String.toUri()` helper, four version-catalog aliases and the legacy mdpi icon's export fringe. The icon remains 48 by 48 pixels: only its near-transparent outer edge (alpha 1–5 out of 255) becomes fully transparent; every interior pixel, other density asset and adaptive icon is unchanged. No artwork is redrawn or rescaled.

| Lint ID | Starting warnings | Treatment |
|---|---:|---|
| `UseKtx` | 46 | Correct 31 existing asynchronous preference edits and one URI conversion, plus an additional migration-editor finding exposed by the first lint rerun. Retain 14 explicit durable editor operations; KTX `edit(commit = true)` returns `Unit` and cannot replace a checked Boolean receipt. Keeping explicit commits also makes the durability boundary auditable. |
| `UseTomlInstead` | 4 | Move desugaring, SQLCipher, SQLite and MockWebServer into the catalog at identical versions/scopes. Preserve SQLCipher's original non-transitive `@aar` artifact contract. |
| `IconLauncherShape` | 1 | Remove only the almost-transparent mdpi export fringe to restore a transparent one-pixel perimeter. |
| `ApplySharedPref` | 8 | Intentionally retain synchronous privacy/lifecycle barriers; asynchronous `apply()` is not a safe substitute. |
| `AndroidGradlePluginVersion` | 2 | Retain pinned Gradle/AGP versions; availability alone does not establish compatibility. |
| `GradleDependency` | 24 | Retain tested dependency pins pending a separate compatibility/security upgrade review. |
| `NewerVersionAvailable` | 10 | Retain tested tool/library pins, including aligned OkHttp and MockWebServer; do not infer that an availability advisory is a vulnerability finding. |

### Why explicit editors remain

`AuthPreferences` keeps synchronous local-mode, health-consent/generation, pending-withdrawal, processor-decision/receipt and session-generation writes. Checked `commit()` results still reject failed persistence before granting permission or performing privacy-sensitive network work. The previously unchecked synchronous barriers also remain synchronous; this lint cleanup does not change their failure policy.

`OwnerDataGate` keeps wipe/resume markers durable before data access can resume. `DatabaseKeyManager` must confirm that the encryption key is stored before a database can use it. `EncryptedCheckoutRequestStore` keeps checkout request save/clear operations synchronous and checked so a restart cannot silently create a new payment identity. The associated `ApplySharedPref` and `UseKtx` findings remain visible by design, not suppressed.

Version availability warnings remain visible in the build script, catalog and wrapper. An upgrade needs its own reviewed Gradle/AGP/Kotlin/Compose/Room/SQLCipher/WorkManager/test-library compatibility matrix, API 24/desugaring checks, encrypted database migrations, worker/account lifecycle tests and appropriate physical-device acceptance. Moving coordinates into a catalog is not permission to upgrade them.

### Validation and scope

Compare all four moved dependencies with their original declarations, including transitivity, artifact extension/type and resolved SHA-256 hashes. Rerun the full Android unit suite, debug APK build, instrumentation APK build and debug lint. Preference regressions explicitly cover asynchronous apply counts, all-fields-before-apply behavior, failure during staging, durable local mode and a failed checked consent-decision commit. Building instrumentation still does not mean running it on a handset. Admin/backend/SQL results from the preceding review remain historical unless those unchanged components are separately rerun.

The feature-branch push receipt records the final lint ID counts, test totals, artifact hashes and remote branch verification. Health sync, Hybrid and checkout remain closed; clinical, provider-policy, hosted-sandbox, physical-device and release approvals above are unchanged.

## Development defaults hardening

Normal Android debug builds now leave diagnostics unconfigured instead of embedding an expired developer-service URL. An explicit `-Pcyclecare.debugBridgeUrl=https://diagnostics.example.test` opt-in accepts only an HTTPS origin with no credentials, path, query or fragment and a valid optional port. Use an approved temporary origin, not the example. Missing or invalid runtime configuration disables pairing/sending before a connection can be opened; release builds retain an empty endpoint and are rejected by the runtime policy. A configured endpoint still requires the owner's explicit pairing and per-snapshot action. See [optional debug diagnostics](remote_debug_architecture.md).

The separate Admin PWA now previews on `127.0.0.1:4173` with host-header protection and no stale temporary hostname. Network binding is an explicit temporary CLI choice; production hosting/provider settings and feature gates are unchanged. The Admin README now describes the actual separate-repository layout rather than an obsolete embedded `admin-pwa` directory.

Verified follow-up: **207 Android unit tests pass**, including six new diagnostic-policy regressions. Debug APK, instrumentation APK and release Kotlin compilation pass. Debug and release lint each retain **58 warnings and zero errors**, with no new warnings or suppressions. The Admin suite has **26 passing tests** and its production build passes. A generated-BuildConfig matrix checks default-disabled behavior, explicit opt-in, rejection of unsafe origins, release isolation and restoration of default generated settings before push.

These results do not establish physical-device/bridge acceptance, clinical approval, provider eligibility or hosted payment settlement. Instrumentation was built, not executed on hardware. Backend/SQL code was unchanged and its preceding results were not rerun for this maintenance pass. Health sync, Hybrid and payment checkout remain source-gated; nothing was merged, deployed or paid for.
