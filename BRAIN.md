# CycleCare — project brain

## Scope and source of truth

This is the project's **current working memory**: architecture, implemented contracts, known limits, release boundaries and the next real decisions. It is not a setup guide, a running conversation log, a backlog dump or evidence of approval. [README.md](README.md) owns installation, commands and local configuration.

Guiding frameworks and build recipes (AI core, tone language, payment routing, learning/persona memory) live in [frameworks/](frameworks/README.md); they are developer guidance, never bundled or compiled with the app.

This package contains the integrated 5-sprint remediation across Android, Supabase, and Admin PWA:

| Project | Implemented State |
|---|---|
| Android Client | Kotlin 2.x, Jetpack Compose, Room 2.7, 5-destination navigation (`TODAY`, `CYCLE`, `ASK`, `YOU`, `SPACE`), purged economy terminology (`Earn/Atelier`), offline-first engines |
| Supabase Backend | 18 migrations applied/defined, double-entry `coin_transactions` ledger, `daily_logs` v2 fields, `profiles` identity extensions, `chat_topics`/`chat_messages`, `safety_events`, `admin-chat-moderation` edge function |
| Admin PWA | React + TypeScript (Vite bundle verified), Feature Flags (`v3_sync_enabled`), Clinical Safety Audit, Circles Moderation, Coin Ledger, Sync Health telemetry |

The repository is a cleaned, integrated source tree. Old Markdown reports are consolidated into architecture specifications and gap analyses (`gap-analysis.md`). Deprecated files retain compatibility wrappers without artificial engagement friction.

## Product and architecture

CycleCare provides local menstrual-health tracking, uncertain calendar estimates and optional server-routed educational information. It is **not** diagnosis, contraception, pregnancy confirmation, emergency dispatch or a substitute for professional care.

- **Android:** Kotlin/Compose, Room 2.7, owner-scoped SQLCipher databases, Android Keystore-backed key protection, WorkManager, and API 24 compatibility through desugaring. Private app backup/device transfer is disabled.
- **Backend:** Supabase Auth, PostgreSQL/RLS and Edge Functions. The server owns provider selection, authorization, authoritative consent, coin ledger recording, and payment settlement; Android is not a trusted settlement authority.
- **Admin:** React/Vite PWA using authenticated, server role-checked REST/RPC endpoints. Provider, merchant and service-role credentials never belong in its browser bundle.

Local mode is not an authenticated cloud identity. Owner, login-session and consent generations protect against account switching, logout/relogin and late responses. Token refresh alone is not a new login.

## Implemented contracts & subsystems

| Subsystem / Contract | Implemented Status | Release State |
|---|---|---|
| **5-Destination Navigation** | `TODAY` (Home), `CYCLE` (Calendar/Arc), `ASK` (Care AI + Daily Log), `YOU` (Profile + Ranks + Insights), `SPACE` (Circles + Atelier) | Implemented & verified in Kotlin compiler |
| **Economy Vocabulary & Mechanics** | "Mining" purged to "Daily Attunement / Earn", "Vault" purged to "Atelier & Rewards", streak revival/punitive mechanics deleted per spec §48 | Implemented via `EarnCoinsUseCase`; `MiningUseCase` deprecated wrapper |
| **Double-Entry Coin Ledger** | `coin_transactions` table with RLS, indexed lookups, balance verification and audit ledger writes | Implemented in migration `20261020130000_coin_transactions_ledger.sql` |
| **Extended Daily Logging (v2)** | `daily_logs` fields for `energy_level`, `vaginal_health`, `urinary_health`, `cervical_mucus`, `ovulation_test`, `bbt_celsius`, `sleep_hours`, `sleep_quality`, `water_glasses`, `pain_intensity` | Implemented in migration `20261020120000_daily_logs_schema_v2.sql` |
| **Identity & Profile Fields** | `profiles` columns: `display_name`, `avatar_url`, `bio`, `rank_badge`, `active_theme` | Implemented in migration `20261020140000_profiles_identity_fields.sql` |
| **Guided Chats (Circles)** | `chat_topics`, `chat_messages` (500-char max), `chat_memberships`, `admin-chat-moderation` edge function | Implemented in migration `20261020150000` & repository layer |
| **Safety Events Audit** | `safety_events` table for red-flag auditing and clinical escalation tracking | Implemented in migration `20261020150000_guided_chats_and_safety_events.sql` |
| **Intelligence Engines** | `PatternEngine` (phase mapping, exp recency weighting, MAD anomaly detection), `SomethingChangedEngine` (90-day baseline shift detector), `AttentionEngine` (multi-tier cards), `CareKnowledgeBase` (22 clinical/educational articles) | Implemented & unit-tested in Android domain |
| **Admin Control Center** | Feature Flags (`v3_sync_enabled`), Clinical Safety Audit, Circles Moderation, Coin Ledger, Sync Health dashboard | Implemented in `AdminPWA/src/components/`, Vite build verified |
| **Health sync and health erasure** | Durable Room outbox/retries, revisions/cursors/tombstones, conflict records and Settings decisions; v3-only validation; unified cloud/local health erasure | **Closed** pending authenticated staging and two-device acceptance |
| **Provider-specific AI consent** | Reviewed manifest disclosure, owner/session-bound revision receipts, pending withdrawal/CAS, stale-response protection and Admin-enforced single-provider selection | Candidate integration needs provider eligibility and staging review |
| **Hybrid Bridge** | Balanced rotation across consented providers (Gemini/Cohere/OpenRouter), failover chain, then system fallback; consent receipts + policy readiness gate every provider | **Released** 2026-10-21 (owner-directed); server-only selection, invisible to clients |
| **PayPal settlement/accounting** | Durable checkout identities, independent order/capture verification, verified webhook evidence, replay-safe credits and refund/reversal/debt accounting | **Checkout closed** pending hosted sandbox and merchant-policy acceptance |

## Intelligence & Safety Architecture

CycleCare enforces a deterministic local safety layer before any server-routed AI interaction:
- **Red Flag Interception:** High-urgency inputs (severe pain, sudden hemorrhage, fainting, acute pelvic distress) trigger immediate emergency/clinical routing locally via `SafetyEngine`. No text is forwarded to LLM endpoints.
- **Pattern Detection:** `PatternEngine` correlates symptoms against estimated cycle phases, applies exponential recency weighting ($e^{-0.03 \cdot \text{daysAgo}}$ with a half-life of ~23 days), and isolates anomalies using Median Absolute Deviation (MAD).
- **Shift Detection:** `SomethingChangedEngine` evaluates the trailing 90-day window against historical baselines, surfacing non-diagnostic observations for sustained pain increases ($\ge 1.5$ average delta) or notable symptom frequency shifts.
- **Educational Knowledge Base:** Curated 22-topic clinical library spanning all 4 cycle phases, red flags, cervical fluid characteristics, BBT biphasic shifts, and somatic wellbeing.

## Admin PWA Control Center

The Admin PWA (`AdminPWA`) provides governance and observability over production data without exposing raw private health details:
- **Feature Flags Manager:** Governs server runtime flags including `v3_sync_enabled` and provider switches.
- **Safety Audit Viewer:** Live inspection of anonymized red-flag clinical safety events.
- **Circles Moderation:** Community topic management (activate, archive, pin) and message moderation.
- **Coin Transaction Ledger:** Double-entry ledger audit log displaying credits, debits, actor IDs, and transaction types.
- **Sync Health Dashboard:** Monitors telemetry across outbox mutations, conflict queue depths, and V3 sync gate status.
- **Atelier & Multipliers:** Catalog reference and reward tier visibility.

## Verified engineering status

| Check | Result |
|---|---|
| Android Unit Tests | **Passed**: `PatternEngineTest` (phase calculations, recency weighting, pattern detection), `SomethingChangedEngineTest` (pain & symptom deviation detection), `EarnCoinsUseCaseTest` (offline resilience, daily attunement), `ClinicalSafetyAcceptanceTest` (red flags, local containment), `SafetyEngineInputTest` (deterministic evaluation) |
| Android Compilation | `compileDebugKotlin` and `compileDebugUnitTestKotlin` passed with 0 errors |
| Admin PWA Build | Vite production build passed in 1.83s (`dist/assets/index-Cr4LD7WV.js` - 259.70 kB) with 0 errors |
| Lint Status | 0 errors, retained advisories deliberate for synchronous lifecycle persistence barriers |
| Database Migrations | 18 ordered SQL migration files in `Cyclecare/supabase/migrations/` |

## What is genuinely unfinished

These items remain real-world external prerequisites and must not be represented as approved:

| Requirement | Exact input or work still needed |
|---|---|
| Clinical sign-off | Named qualified licensed clinician, intended population/jurisdictions, reviewed escalation/calendar/pregnancy wording, supported languages/accessibility and recorded actual approval |
| Device/staging acceptance | Authorized connected host and test handset(s), staging accounts and two-device tests for Keystore/SQLCipher recovery, account switching, outbox restarts, conflicts, erasure/revocation, keyboard and accessibility |
| Processor eligibility | Account/DPA, retention, region, audience/age, use-case and upstream/model evidence; Gemini also needs a valid eligible server-side replacement key |
| Hosted PayPal sandbox | Sandbox merchant client ID/secret, webhook ID, buyer access, approved return/cancel origins and agreed ambiguous/partial-refund policy; then hosted lifecycle evidence |
| Approved payment rails | Four rails decided by the owner, each fail-closed until its provider credentials and settlement/reversal contracts are independently verified: M-Pesa via PayHero, PayPal wallet (standard), card via PayPal ACDC, crypto via OKX USDT/USDC on X Layer |
| Recurring subscriptions | Renewal/expiry/cancellation/refund entitlement lifecycle is not implemented; an approved product contract is needed first |
| Full account/provider deletion | Retention/lifecycle decisions, provider erasure mechanisms, safeguards and acceptance are not supplied by health erasure |
| Rollout/store release | Exact target-catalog comparison, reviewed forward migration, staging acceptance, protected signing access and separate explicit merge/deploy/publish authorization |

## Database and operational boundaries

Keep the ordered `Cyclecare/supabase/migrations/` files as historical inputs. Key migrations added:
- `20261020120000_daily_logs_schema_v2.sql`
- `20261020130000_coin_transactions_ledger.sql`
- `20261020140000_profiles_identity_fields.sql`
- `20261020150000_guided_chats_and_safety_events.sql`

Before rollout, compare the real catalog, grants, ownership, function bodies and applied history; create an exact reviewed forward-only migration. Never rewrite applied history, run a blanket database push, or execute test/bootstrap SQL on a user database.

## Keep this brain useful

Update this file only when a source contract, validated result, approval or real blocker changes. Keep setup commands in README.md. Separate implemented-but-gated features from missing functionality. Never infer clinical/device/provider/merchant approval from tests, secret presence, source commits or an APK. Do not append chat transcripts, stale reports, credentials, logs or speculative success claims.
