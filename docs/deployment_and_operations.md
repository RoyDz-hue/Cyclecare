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
