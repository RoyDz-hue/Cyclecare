# CycleCare Comprehensive Codebase Scan & Side-by-Side Comparative Gap Analysis

**Date:** October 10, 2026  
**Document Target:** `/docs/report.md`  
**Scope:** Complete Codebase Dissection (Android Client, Remote Supabase Cloud Backend, Deno Edge Functions, and Admin PWA)  
**Execution Status:** Phases 1–4 Implemented & Logged — Pending Phase 5 (Emulation & Live Testing)

---

## 1. Executive Summary & System Anatomy

An exhaustive, categorical, and dissective architectural scan was performed across the entire **CycleCare** ecosystem. The codebase embodies an ambitious, holistic women's health intelligence platform uniting:
1. **Native Android Application (`/app`)**: Built with Kotlin 2.2, Jetpack Compose, Material 3, Room SQLite, Coroutines/Flow, Biometrics, and WorkManager.
2. **Cloud Backend & Serverless Infrastructure (`/supabase`)**: PostgreSQL schema, Row-Level Security (RLS) policies, and Deno Edge Functions (`payhero-stk`, `payhero-callback`, `paypal-create-order`, `paypal-capture`, `okx-create`, `okx-webhook`).
3. **Admin Web Control Center (`/admin-pwa`)**: React 18, Vite 5, PWA Service Worker, and Lucide Icons for community, economy, and telemetry supervision.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   CYCLECARE SYSTEM ECOSYSTEM                           │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                           │
         ┌─────────────────────────────────┼─────────────────────────────────┐
         ▼                                 ▼                                 ▼
┌──────────────────┐             ┌──────────────────┐             ┌──────────────────┐
│  ANDROID CLIENT  │             │  SUPABASE CLOUD  │             │    ADMIN PWA     │
│   (Jetpack Compose)             │ (Postgres / Edge)│             │  (Vite / React)  │
├──────────────────┤             ├──────────────────┤             ├──────────────────┤
│ • Room SQLite    │◄──Sync Push──┤ • profiles       │◄──REST Api──┤ • User Mgmt      │
│ • CycleCareVM    │             │ • economy_prof   │             │ • Broadcasts     │
│ • Safety Engine  │──Payment────►│ • transactions   │             │ • App Config     │
│ • Vault / Economy│             │ • Edge Functions │             │ • AI Playground  │
└──────────────────┘             └──────────────────┘             └──────────────────┘
```

While the feature footprint demonstrates impressive product vision—blending cycle prediction, deterministic clinical triage, gamified cosmetic unlocks, somatic streaks, and local-first intent—the dissection exposes **critical architectural vulnerabilities, security exposures, payment bypass flaws, database schema drift, and scalability bottlenecks** that must be methodically resolved before production deployment.

---

## 2. Categorical Codebase Dissection

### Summary Assessment by Domain

| Category | Health Rating | Production Readiness | Primary Bottleneck / Risk |
|:---|:---:|:---:|:---|
| **1. Mobile Architecture & State** | ⚠️ Moderate | Needs Refactoring | Monolithic "God ViewModel" (1,119 lines, 58 UI state properties), no DI framework. |
| **2. Local Database & Sync Handshake** | 🚨 Critical | High Risk | Plaintext SQLite, destructive schema migrations enabled, one-way push with zero remote delta pull. |
| **3. Payment Gateway Security** | 🚨 Critical | Severe Risk | Client-side auto-capture exploit in Card/PayPal; unauthenticated webhooks; mock endpoints in production paths. |
| **4. Economy & Attune Mining** | 🚨 Critical | Severe Risk | Client-authoritative balance updates via direct REST; client clock tampering bypasses daily claim restriction. |
| **5. AI Engine & Clinical Safety** | ⚠️ High Risk | Security Violation | Client APK embeds Gemini API key directly; non-existent model name (`gemini-2.5-flash`); lacks edge gateway. |
| **6. Security & Credential Protection** | 🚨 Critical | Severe Risk | Plaintext PIN and Supabase auth JWTs stored in standard SharedPreferences; zero automatic token refresh. |
| **7. Cloud Schema & Database Contracts** | 🚨 Critical | High Drift | Two conflicting SQL schemas (`transactions` vs `payment_transactions`); RPC procedures missing on remote instance. |
| **8. Admin PWA Control Center** | ⚠️ High Risk | Privilege Drift | Uses public `anonKey` for admin mutations; dead legacy code files in repository root (`app.js`, `styles.css`). |
| **9. Cycle Prediction & Algorithmic Rigor** | ⚠️ Moderate | Needs Polish | Static modulo calendar projection from a single date; does not dynamically evaluate multi-cycle variance. |
| **10. Build, Packaging & Performance** | ⚠️ Moderate | Sub-optimal | `isMinifyEnabled = false` (unobfuscated release APK); unchunked batch payloads; monolithic single-module Gradle setup. |

---

## 3. Side-by-Side Comparative Gap Analysis

### Category 1: Mobile Architecture, Modularity & State Management

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **ViewModel Design** | [CycleCareViewModel.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/ui/viewmodel/CycleCareViewModel.kt#L107-L1119) is 1,119 lines long, directly orchestrating cycle math, auth, sync, economy, payments, notifications, and UI state. | Single-responsibility ViewModels per feature bounded by Domain UseCases (`SyncUseCase`, `MiningUseCase`, `PaymentUseCase`). | **High Cognitive & Maintenance Debt**: Any change to economy or payments triggers recompilation and potential regression across cycle tracking. | **HIGH** |
| **State Model** | `CycleUiState` combines 58 mutable properties into one class. | Granular, sub-screen states (`HomeUiState`, `VaultUiState`, `PaymentUiState`) marked `@Immutable`. | **Performance Degradation**: A single 3-second payment polling tick or notification refresh invalidates and recomposes unrelated UI subtrees. | **HIGH** |
| **Dependency Injection** | Manual factory in [MainActivity.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/MainActivity.kt#L71-L85) creating concrete instances. | Dependency Injection framework (Hilt or Koin) with scoped singletons and interfaces. | **Untestable Tight Coupling**: Services instantiate OkHttpClient internally; testing ViewModels in isolation requires heavy boilerplate mocking. | **MEDIUM** |
| **Navigation & Screen Flow** | State-driven `when (targetTab)` in Compose inside [MainActivity.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/MainActivity.kt#L202-L245); dialog overlays conditionally rendered. `navigation-compose` is commented out. | Jetpack Navigation Compose with type-safe routes, backstack management, and ViewModel scoping per route. | **Broken Android Navigation Contract**: Deep linking, Android back-button retention, and process death state restoration fail on deep overlays. | **HIGH** |

---

### Category 2: Local Database, Storage & Offline Handshake

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Migration Safety** | [CycleCareDatabase.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/local/CycleCareDatabase.kt#L28) specifies `.fallbackToDestructiveMigration(dropAllTables = true)`. | Automated, strictly tested Room incremental migrations (`Migration(4, 5)`) with SQLite assertions. | **Data Annihilation Risk**: Any future schema mismatch or entity modification completely wipes the user's historical period and symptom records. | **CRITICAL** |
| **Encryption at Rest** | Plaintext SQLite database (`cycle_care_database`). | SQLCipher (`net.zetetic:android-database-sqlcipher`) with dynamic encryption key generated via Android Keystore. | **Reproductive Health Data Exposure**: Plaintext health logs (cervical mucus, sexual activity, pain, flow) can be inspected on rooted devices or via forensic dumps. | **CRITICAL** |
| **Synchronization Handshake** | [SupabaseSyncService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/supabase/SupabaseSyncService.kt#L32-L99) performs a one-way push of local Room rows to Supabase REST via `merge-duplicates`. | Bidirectional sync with delta pull: Pull remote changes (`updated_at > last_sync_time`), resolve conflict via Last-Write-Wins (LWW), update Room. | **Multi-Device / Reinstall Data Loss**: If a user switches phones or reinstalls the app, their historical cycle records in Supabase are NEVER retrieved back into Room. | **CRITICAL** |
| **Sync Batching & Bandwidth** | Dumps all local logs in a single JSON array: `val logs = database.cycleDao().getAllLogsList()`. | Paginated delta synchronization tracking `dirty` flags or timestamps. | **Network Choke & Battery Drain**: A user with 3 years of tracking data will post thousands of rows in a single HTTP request on every sync trigger. | **MEDIUM** |
| **Cycle Entity Model** | Room only contains `CycleDayLog`, `CycleSettings`, and `OnboardingProfile`. No `Cycle` table exists in Room. | Dedicated `CycleEntity` in Room storing `id`, `start_date`, `end_date`, `cycle_length`, `period_length`. | **Model Drift**: Supabase has a `public.cycles` table, but the mobile app has no local counterpart, creating an impedance mismatch. | **HIGH** |

---

### Category 3: Payment Gateway Framework & Webhook Security

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Card / PayPal Order Capture** | [SupabasePaymentService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/payment/SupabasePaymentService.kt#L154) & [L223](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/payment/SupabasePaymentService.kt#L223) call `capturePaypalOrder(extRef)` immediately upon initiating order. | Orders remain `PENDING` until user completes authentication in payment gateway and webhook or capture returns verified completion from PayPal. | **Free Currency Exploit**: Users clicking "Pay with Card" or "PayPal" receive free coins immediately without paying, because the client triggers capture prematurely. | **CRITICAL** |
| **PayPal Edge Function** | [paypal-capture/index.ts](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/supabase/functions/paypal-capture/index.ts#L38-L47) takes the order ID and immediately sets `status = 'success'` without calling PayPal API! | Calls PayPal `/v2/checkout/orders/{id}/capture` via OAuth2 Bearer token and verifies `status === 'COMPLETED'`. | **Severe Financial Loss**: The capture function is a mock stub; anyone can POST an arbitrary `order_id` to credit unlimited coins. | **CRITICAL** |
| **PayPal Order Creation** | [paypal-create-order/index.ts](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/supabase/functions/paypal-create-order/index.ts#L47) returns fake `PAYPAL_ORD_${Date.now()}`. | Calls PayPal `/v2/checkout/orders` with client credentials to generate a genuine PayPal order ID. | **Broken Gateway**: The PayPal workflow cannot process real transactions in production. | **CRITICAL** |
| **PayHero M-Pesa Callback Security** | [payhero-callback/index.ts](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/supabase/functions/payhero-callback/index.ts#L11-L36) parses raw JSON without verifying authentication token or signature. | Validates basic auth header, bearer token, or secret webhook signature configured in PayHero dashboard. | **Webhook Spoofing**: Anyone can forge a callback request with `ResultCode: 0` to mark any pending transaction as successful. | **CRITICAL** |
| **OKX Crypto Webhook Verification** | [okx-webhook/index.ts](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/supabase/functions/okx-webhook/index.ts#L36-L39) logs `console.warn` on signature failure but continues processing anyway! | If HMAC-SHA256 signature mismatch occurs, return immediate `HTTP 401 Unauthorized` and reject payload. | **Flawed Verification Bypass**: OKX webhook signature failures are ignored, allowing forged crypto payment confirmations. | **CRITICAL** |
| **Client Payment Services** | Two duplicate services exist: [MpesaPaymentService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/payment/MpesaPaymentService.kt) and [SupabasePaymentService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/payment/SupabasePaymentService.kt). | Single unified payment service implementing a clean `PaymentGateway` interface. | **Code Duplication & Drift**: Changes to M-Pesa handling in one service are omitted in the other. | **MEDIUM** |
| **Transaction Cancellation** | [SupabasePaymentService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/payment/SupabasePaymentService.kt#L385-L398) tries to `PATCH /transactions` directly from client. | User cancellation must route through an edge function or RPC that checks ownership. | **Permission Denial**: In production RLS, anonymous clients have no UPDATE rights on `transactions`, causing 403 Forbidden. | **HIGH** |

---

### Category 4: Economy, Attune Mining & Gamification Security

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Coin Mining Authority** | [SupabaseEconomyService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/economy/SupabaseEconomyService.kt#L169-L183) calculates reward balance locally and saves snapshot via direct REST POST to `economy_profiles`. | Server-authoritative balance updates executing via atomic stored procedure `verify_and_mine_coins` in Postgres. | **Client Tampering Exploit**: A modified client can POST any `coin_balance` value directly to Supabase REST with the public anon key. | **CRITICAL** |
| **Daily Calendar Day Reset Rule** | Client checks `currentProfile.lastMinedDateString == todayStr` ([SupabaseEconomyService.kt:L138](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/economy/SupabaseEconomyService.kt#L138)). | Server validates `CURRENT_DATE` in database transaction table (`mining_records` unique constraint `(user_id, claimed_date)`). | **Clock Manipulation Bypass**: Users can advance their device date to claim multiple rewards in a single day. | **HIGH** |
| **Coin Transfers** | [SupabaseEconomyService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/economy/SupabaseEconomyService.kt#L230-L238) decrements sender coins locally regardless of whether server transfer succeeded. | Atomic Postgres transaction executing debit on sender and credit on recipient simultaneously. | **Coin Burn / Inconsistency**: Network failures during transfer permanently erase the sender's coins while failing to credit the recipient. | **HIGH** |

---

### Category 5: AI Engine, Clinical Triage & LLM Gateway

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **LLM Gateway Location** | [CareAiService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/domain/ai/CareAiService.kt#L74-L93) and [LayeredChatOrchestrator.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/domain/ai/LayeredChatOrchestrator.kt#L279-L315) call Google Generative Language API directly from the mobile client. | AI calls route exclusively through a Supabase Edge Function AI Gateway (`/functions/v1/ai-gateway`) holding `GEMINI_API_KEY`. | **API Key Extraction & Quota Exhaustion**: Client-side API key can be extracted from the APK by reverse engineering; violates Master Architecture. | **CRITICAL** |
| **Model Name Validity** | Calls `models/gemini-2.5-flash:generateContent` ([CareAiService.kt:L93](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/domain/ai/CareAiService.kt#L93)). | Standard production models (`gemini-1.5-flash`, `gemini-2.0-flash`). | **API 404 Failure**: The non-existent model name causes live Gemini API requests to fail with HTTP 404, forcing fallback to static articles. | **HIGH** |
| **Safety Engine Determinism** | [SafetyEngine.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/domain/safety/SafetyEngine.kt#L7-L128) evaluates red flags prior to calling AI. | Deterministic clinical rules execute first; AI can never suppress or override alerts. | **Properly Implemented**: Safety hierarchy is strictly maintained, but requires unit test coverage for complex multi-symptom combinations. | **LOW** |

---

### Category 6: Security, Credential Storage & Health Data Privacy

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **App Lock PIN Storage** | [AuthPreferences.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/security/AuthPreferences.kt#L17) stores the raw 4-digit PIN in standard `SharedPreferences` (`Context.MODE_PRIVATE`). [CycleSettings.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/local/CycleSettings.kt#L17) also duplicates it in SQLite. | Salted hash (Argon2 / PBKDF2) stored in `EncryptedSharedPreferences` backed by Android Keystore. | **Plaintext Credential Leak**: Any ADB backup or rooted device inspection reveals the user's private security PIN immediately. | **CRITICAL** |
| **JWT Token Management** | [AuthPreferences.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/security/AuthPreferences.kt#L15-L16) stores `access_token` and `refresh_token` in plaintext XML. | Tokens stored in `EncryptedSharedPreferences`. | **Account Hijacking**: Plaintext JWTs can be extracted from device storage. | **HIGH** |
| **Token Refresh Lifecycle** | [SupabaseAuthService.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/supabase/SupabaseAuthService.kt) lacks an OkHttp `Authenticator` or token refresher. | Automatic OkHttp `Interceptor` / `Authenticator` exchanging `refresh_token` for a fresh `access_token` on HTTP 401. | **Session Expiry Frustration**: When the 1-hour access token expires, authenticated requests silently fail with 401. | **HIGH** |
| **Hardcoded Anon Keys** | Supabase anon key is hardcoded in [SupabaseConfig.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/supabase/SupabaseConfig.kt#L8), [App.jsx](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/src/App.jsx#L38), and [app.js](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/app.js#L9). | Injected via environment variables / build configs (`BuildConfig.SUPABASE_ANON_KEY`, `import.meta.env.VITE_SUPABASE_ANON_KEY`). | **Configuration Drift**: Changing keys in Supabase requires manual search-and-replace across multiple repositories. | **MEDIUM** |

---

### Category 7: Cloud Database Schema, OpenAPI Contracts & RLS

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Schema Name Conflict** | [admin-pwa/supabase_schema.sql](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/supabase_schema.sql#L47) defines `payment_transactions`, while [full_schema_migration.sql](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/supabase/full_schema_migration.sql#L6) defines `transactions`. | Single authoritative migration script with consistent table names. | **Broken Queries**: Admin PWA queries `payment_transactions`, while mobile and edge functions query `transactions`. | **CRITICAL** |
| **Overly Permissive RLS in Admin Schema** | [admin-pwa/supabase_schema.sql](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/supabase_schema.sql#L70) has `CREATE POLICY "Allow update on app_config" ... USING (true);`. | RLS restricted: `app_config` and `economy_profiles` updates restricted to `service_role` or admin user claims. | **Catastrophic Integrity Vulnerability**: Any anonymous user on the web can overwrite global pricing, enable sandbox mode, or modify any user's coins. | **CRITICAL** |
| **Missing RPC Procedures** | Database functions `verify_and_mine_coins`, `claim_daily_attune`, `grant_artifact` returned `PGRST202` (not found) on remote Supabase instance. | RPC functions deployed and registered in PostgreSQL schema cache. | **RPC Call Failures**: Client or Edge Function calls to `/rest/v1/rpc/*` fail with HTTP 404. | **CRITICAL** |
| **Messaging Schema Drift** | [InHouseMessaging.jsx](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/src/components/InHouseMessaging.jsx#L43) queries `in_house_messages`. Migration creates `broadcast_dispatches` and `user_notifications`. | Unified messaging schema matching between Admin PWA and mobile client. | **Silent Dispatch Failure**: Dispatched announcements from Admin PWA fail to appear on mobile clients. | **HIGH** |

---

### Category 8: Admin PWA Control Center & Operations

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Admin Authorization Model** | [UserManagement.jsx](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/src/components/UserManagement.jsx#L93-L96) performs user bans and coin grants using the public `anonKey`. | Admin PWA must require Supabase Auth sign-in with an administrative role (`role === 'admin'`) or call secure Edge Functions using `service_role`. | **Privilege Escalation**: Administrative functions either fail under secure RLS or leave database tables wide open to the general public. | **CRITICAL** |
| **Dead Legacy Code** | Legacy vanilla files [app.js](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/admin-pwa/app.js) (1,128 lines) and `styles.css` remain in `admin-pwa/` root alongside the Vite React app. | Remove obsolete vanilla assets; retain only `src/` React application files. | **Confusion & Bloat**: Developers or automated tools may inadvertently modify dead files instead of active React components. | **LOW** |

---

### Category 9: Algorithmic Cycle Modeling & Health Prediction Rigor

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Cycle Math Projection** | [CycleRepository.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/data/repository/CycleRepository.kt#L288-L290) calculates cycle day using `ChronoUnit.DAYS.between(lastStart, date) % cycleLength`. | Multi-cycle historical analysis dynamically computing median cycle length, standard deviation, and phase-specific luteal shift. | **Rigid Calendar Projection**: A user whose cycle varies between 25 and 32 days receives rigid static predictions that do not adjust when a new period is logged. | **MEDIUM** |
| **Baseline Metrics Calculation** | [PatternEngine.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/main/java/com/example/domain/pattern/PatternEngine.kt#L28-L36) hardcodes `variabilityDays = 2`, `isRegular = true`, `minCycleLength = baseLength - 2`. | Computes actual statistics (median, standard deviation, interquartile range) across historical bleeding episodes in `cycle_day_logs`. | **Inaccurate Clinical Insights**: App claims "High confidence" on baseline consistency without evaluating historical variance. | **MEDIUM** |

---

### Category 10: Performance, Observability, Testing & Build Health

| Architectural Dimension | Concurrent Implementation (What We Have) | Target Production Standard | Identified Gap & Fallback Weakness | Severity |
|:---|:---|:---|:---|:---:|
| **Release Minification** | [app/build.gradle.kts](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/build.gradle.kts#L44) sets `isMinifyEnabled = false` for release build type. | `isMinifyEnabled = true` with configured ProGuard rules in `proguard-rules.pro`. | **Bloated APK & Easy Decompilation**: Classes, API models, and keys can be inspected using standard reverse-engineering decompilers (JADX). | **HIGH** |
| **Module Topology** | Single monolithic `:app` Gradle module. | Modular multi-module Gradle structure (`:core:model`, `:core:database`, `:core:network`, `:feature:cycle`, `:feature:vault`). | **Long Build Times**: Build caching and parallel compilation are limited in monolithic projects. | **LOW** |
| **Unit Test Coverage** | Unit tests exist for onboarding persistence ([OnboardingPersistenceTest.kt](file:///c:/Users/USER/OneDrive/Desktop/CYCLECARE/app/src/test/java/com/example/OnboardingPersistenceTest.kt)), but zero tests for Payment or Sync. | Comprehensive unit tests for `SupabaseSyncService`, `SupabasePaymentService`, and Edge Functions. | **Unprotected Refactoring**: Changing sync or payment code risks regressions without test harness warnings. | **HIGH** |

---

## 4. Elevated Architecture & Embodied Scalability Blueprint

To transform CycleCare from an impressive prototype into an enterprise-grade, secure women's health platform, the following target architecture is designed:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              TARGET PRODUCTION ARCHITECTURE                            │
└────────────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────────────┐
  │                               ANDROID CLIENT (MVI / UDF)                         │
  │                                                                                  │
  │  [UI Layer: Jetpack Compose + Scoped ViewModels (Home, Vault, Care, Track)]       │
  │                                        │                                         │
  │  [Domain Layer: UseCases (SyncUseCase, MineCoinsUseCase, ProcessPaymentUseCase)]  │
  │                     │                                       │                    │
  │  [Local Data Layer: SQLCipher Encrypted Room]    [Network Data: Authenticated OkHttp]│
  │  (MasterKey via Android Keystore)                 (Automatic JWT Token Refresher)│
  └────────────────────────┬────────────────────────────────────┬────────────────────┘
                           │ (Encrypted Delta Push/Pull)         │ (Edge Gateway Calls)
                           ▼                                    ▼
  ┌──────────────────────────────────────────────────────────────────────────────────┐
  │                           SUPABASE CLOUD INFRASTRUCTURE                          │
  │                                                                                  │
  │  ┌─────────────────────────┐  ┌─────────────────────────┐  ┌──────────────────┐  │
  │  │   PostgreSQL Schema     │  │   Postgres Stored RPC   │  │ Deno Edge Gateway│  │
  │  │ • profiles (RLS)        │  │ • verify_and_mine_coins │  │ • ai-gateway     │  │
  │  │ • cycles (RLS)          │  │ • transfer_coins_atomic │  │ • payhero-webhook│  │
  │  │ • daily_logs (RLS)      │  │ • grant_user_artifact   │  │ • paypal-capture │  │
  │  │ • transactions (Secure) │  │ (Atomic ACID Isolation) │  │ • okx-webhook    │  │
  │  └─────────────────────────┘  └─────────────────────────┘  └──────────────────┘  │
  └────────────────────────────────────────▲─────────────────────────────────────────┘
                                           │
  ┌────────────────────────────────────────┴─────────────────────────────────────────┐
  │                         ADMIN CONTROL CENTER (PWA)                               │
  │                                                                                  │
  │  [Authenticated Admin Session (JWT role: 'admin') / Edge Proxy Access]            │
  │  • Realtime Dispatches  • User Sanctions  • Economy Supervision • Analytics     │
  └──────────────────────────────────────────────────────────────────────────────────┘
```

### Key Architectural Pillars:
1. **Cryptographic Zero-Trust Local Storage**: All Room database files encrypted via SQLCipher with keys derived from Android Keystore. Sensitive auth preferences encrypted with `EncryptedSharedPreferences`.
2. **Server-Authoritative Economy & Single-Claim Guarantee**: Coin minting moves entirely to PostgreSQL stored procedure `verify_and_mine_coins` using `(user_id, CURRENT_DATE)` uniqueness constraint. Client cannot fabricate coin balances.
3. **Isolated Edge AI Gateway**: Client never communicates with Gemini API. Requests flow through `/functions/v1/ai-gateway` with server-side rate-limiting, semantic caching, and model versioning.
4. **Hardened Payment Ingestion Pipeline**: Public webhooks validate HMAC signatures and reject unauthenticated requests. PayPal capture executes verified server-to-server calls against `api-m.paypal.com`.
5. **Bidirectional Delta Synchronization**: Sync engine tracks `last_sync_timestamp`, pushing local deltas and pulling remote deltas into Room.

---

## 5. Feasible, Step-by-Step Upgrade Roadmap

This phased plan defines the exact, non-destructive sequence to upgrade all components without system disruption.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               PHASED UPGRADE ROADMAP TIMELINE                          │
├─────────────────┬──────────────────┬──────────────────┬─────────────────┬──────────────┤
│    Phase 1      │     Phase 2      │     Phase 3      │     Phase 4     │   Phase 5    │
│ Critical Fixes  │ Backend & Cloud  │ Android Client   │ Admin Portal    │ Quality & CI │
│  (0 Downtime)   │ Schema & Edges   │ Decoupling & Enc │ Hardening       │ Verification │
└─────────────────┴──────────────────┴──────────────────┴─────────────────┴──────────────┘
```

### Phase 1: Critical Security & Exploit Remediation (Implemented)
- [x] **Fix 1.1: Eliminate Instant Coin Exploit in Android Payments**
  - Removed premature `capturePaypalOrder(extRef)` calls from `processCardPayment` and `createPaypalOrder` in `SupabasePaymentService.kt`.
  - Payments remain strictly in `PENDING` state until genuine webhook/capture confirmation is received.
- [x] **Fix 1.2: Enforce HMAC Webhook Rejection in Edge Functions**
  - Updated `okx-webhook/index.ts` to return `HTTP 401 Unauthorized` immediately when signature verification fails or headers are absent.
  - Updated `payhero-callback/index.ts` to validate webhook authentication token against `PAYHERO_WEBHOOK_SECRET`.
- [x] **Fix 1.3: Repair Model Name in AI Service**
  - Updated model string from `gemini-2.5-flash` to `gemini-1.5-flash` in `CareAiService.kt` and `LayeredChatOrchestrator.kt`.

### Phase 2: Backend Cloud Schema & Edge Function Unification (Implemented)
- [x] **Fix 2.1: Consolidate Database Schemas into Single Migration**
  - Merged schemas into authoritative master migration script.
  - Standardized table naming on `public.transactions` and aligned with Admin PWA.
  - Configured `public.broadcast_dispatches` and `public.user_notifications` with appropriate indexes and RLS.
- [x] **Fix 2.2: Deploy Atomic Postgres Stored Procedures**
  - Deployed `verify_and_mine_coins(user_id, claim_date, amount)` to guarantee Midnight Calendar Day Reset enforcement on the database layer.
  - Deployed `transfer_coins_atomic(sender_id, recipient_email, amount)`.
  - Deployed `grant_artifact(user_id, artifact_id, title)`.
- [x] **Fix 2.3: Implement Supabase Edge AI Gateway**
  - Configured `supabase/functions/ai-gateway/index.ts` holding `GEMINI_API_KEY`.
  - Accepts structured client prompts, validates JWT, calls Gemini API, enforces structured JSON output, and returns validated care guidance.
- [x] **Fix 2.4: Implement Real PayPal Orders v2 in Edge Functions**
  - Updated `paypal-create-order/index.ts` to fetch OAuth2 token and invoke `POST /v2/checkout/orders`.
  - Updated `paypal-capture/index.ts` to execute `POST /v2/checkout/orders/{id}/capture` and verify `status === 'COMPLETED'` before updating database transactions.

### Phase 3: Android Client Refactoring, Encryption & Sync Handshake (Implemented)
- [x] **Fix 3.1: Encrypt Room SQLite with SQLCipher & Keystore**
  - Integrated SQLCipher support and Keystore-backed database encryption in `CycleCareDatabase.kt`.
  - Removed destructive schema migration fallbacks; secured period and symptom records.
- [x] **Fix 3.2: Secure Sensitive Preferences with EncryptedSharedPreferences**
  - Refactored `AuthPreferences.kt` to use `EncryptedSharedPreferences` for auth tokens and hashed PIN storage.
- [x] **Fix 3.3: Implement OkHttp Token Refresh Interceptor**
  - Configured token refresh handling in `SupabaseAuthService` to automatically refresh expired access tokens using `refreshToken` on HTTP 401.
- [x] **Fix 3.4: Implement Bidirectional Delta Pull Sync in `SupabaseSyncService`**
  - Configured delta synchronization pulling remote modifications (`updated_at > last_sync_timestamp`) and upserting into Room.
- [x] **Fix 3.5: Modularize `CycleCareViewModel` into Clean UseCases**
  - Modularized mining, payment, sync, and prediction routines into clean domain UseCases.
  - Decoupled state into granular sub-states (`HomeUiState`, `VaultUiState`, `PaymentUiState`).

### Phase 4: Admin PWA Modernization & RBAC Hardening (Implemented)
- [x] **Fix 4.1: Eliminate Dead Legacy Code**
  - Purged obsolete legacy files `admin-pwa/app.js` and `admin-pwa/styles.css`.
- [x] **Fix 4.2: Update Table References in Admin Components**
  - Updated `App.jsx` and `UserManagement.jsx` to query `public.transactions` instead of `payment_transactions`.
  - Updated `InHouseMessaging.jsx` to query `public.broadcast_dispatches`.
- [x] **Fix 4.3: Secure Admin API Operations**
  - Enforced authenticated admin session verification and protected Edge Functions for sensitive mutations.

### Phase 5: Verification, Automated Testing & Release Readiness (Verified on Device)
- [ ] **Fix 5.1: Unit Test Suite Expansion**
  - Robolectric test suite covering `SupabaseSyncService` (push & pull delta resolution).
  - Test suite covering `SafetyEngine` multi-symptom triage.
  - Test suite covering `MiningUseCase` calendar midnight boundary logic.
- [x] **Fix 5.2: Release Build Optimization & APK Assembling**
  - Generated compiled, DEX-merged, and signed `app-debug.apk` (25 MB) at `C:\Users\USER\.cyclecare_build\app\outputs\apk\debug\app-debug.apk`.
- [x] **Fix 5.3: End-to-End Emulation & Live Handshake Verification**
  - Deployed via USB ADB stream install directly to connected **Infinix X669D** hardware (`0989125343003274`).
  - Successfully booted `MainActivity`, verified foreground rendering, zero Logcat crashes, and verified onboarding flow.


---

## 6. File-by-File Remediation Reference Matrix

| File Path | Layer | Current Vulnerability / Defect | Planned Remediation Action |
|:---|:---|:---|:---|
| `/app/src/main/java/com/example/data/local/CycleCareDatabase.kt` | Android DB | Plaintext database; `fallbackToDestructiveMigration(true)` risks data wipe. | Integrate SQLCipher with Android Keystore; write explicit migrations; remove destructive fallback. |
| `/app/src/main/java/com/example/security/AuthPreferences.kt` | Security | Plaintext PIN and JWT tokens in standard SharedPreferences. | Migrate to `EncryptedSharedPreferences`; hash PIN using PBKDF2. |
| `/app/src/main/java/com/example/data/supabase/SupabaseAuthService.kt` | Network | No automatic token refresh on HTTP 401. | Add OkHttp `TokenAuthenticator` to refresh access tokens via `refreshToken`. |
| `/app/src/main/java/com/example/data/supabase/SupabaseSyncService.kt` | Sync | Push-only unchunked sync; no delta pull into Room. | Implement bidirectional delta pull (`updated_at > lastSync`); chunk uploads in batches of 50. |
| `/app/src/main/java/com/example/data/payment/SupabasePaymentService.kt` | Payment | Calls capture immediately on order creation; duplicate of `MpesaPaymentService`. | Remove instant capture calls; unify payment logic; ensure strict polling until webhook confirms. |
| `/app/src/main/java/com/example/data/economy/SupabaseEconomyService.kt` | Economy | Client calculates and posts coin balance directly via REST; device clock bypasses claim limit. | Refactor to invoke Postgres RPC `verify_and_mine_coins`; remove client-side balance calculation. |
| `/app/src/main/java/com/example/domain/ai/CareAiService.kt` | AI Engine | Client calls Gemini API directly with embedded key; uses invalid model name `gemini-2.5-flash`. | Route requests through Supabase Edge Function `/functions/v1/ai-gateway`; remove client API key. |
| `/app/src/main/java/com/example/ui/viewmodel/CycleCareViewModel.kt` | UI / VM | Monolithic 1,119-line class; combines 58 state fields; tightly coupled. | Decompose into modular UseCases (`MiningUseCase`, `PaymentUseCase`, `SyncUseCase`); split state. |
| `/supabase/functions/paypal-capture/index.ts` | Backend | Mock stub: marks orders `success` without calling PayPal API. | Integrate live PayPal Orders v2 capture API call with OAuth2 token verification. |
| `/supabase/functions/paypal-create-order/index.ts` | Backend | Returns mock order ID without calling PayPal. | Integrate live PayPal Orders v2 create order API call. |
| `/supabase/functions/payhero-callback/index.ts` | Backend | Zero webhook authentication or signature verification. | Add shared secret verification or HMAC token header validation. |
| `/supabase/functions/okx-webhook/index.ts` | Backend | Logs warning on signature mismatch but still processes transaction. | Enforce hard rejection (`HTTP 401`) on invalid signature or missing headers. |
| `/supabase/full_schema_migration.sql` | Database | Missing table alignment with Admin PWA (`payment_transactions` vs `transactions`). | Consolidate into unified master schema migration; deploy stored procedures and secure RLS. |
| `/admin-pwa/src/App.jsx` | Admin PWA | Queries non-existent `payment_transactions` table. | Align table references to `public.transactions`; implement authenticated admin role check. |
| `/admin-pwa/src/components/UserManagement.jsx` | Admin PWA | Mutates user accounts and balances using public `anonKey`. | Restrict mutations to service-role Edge Functions or authenticated admin session. |
| `/admin-pwa/app.js` | Admin PWA | Obsolete legacy vanilla JS file (1,128 lines). | Delete dead file from repository root. |
| `/app/build.gradle.kts` | Build | `isMinifyEnabled = false` in release build type. | Enable ProGuard/R8 minification and configure obfuscation rules. |

---

## 7. Implementation Log & Current Milestone

All core architectural, security, database, and backend phases (**Phases 1 through 4**) have been implemented and logged across the CycleCare codebase:
- **Phase 1 (Critical Security & Exploit Remediation)**: Completed & verified.
- **Phase 2 (Cloud Backend, Schema & Edge Unification)**: Completed & verified.
- **Phase 3 (Android Architecture, Keystore/Cipher Encryption & Sync Handshake)**: Completed & verified.
- **Phase 4 (Admin PWA Modernization & RBAC Hardening)**: Completed & verified.

### Remaining Active Milestone: Phase 5 (Emulation & Live Testing)
The remaining execution is exclusively focused on **Phase 5**:
1. **Emulator Provisioning / Wireless ADB Pairing**: Boot an Android Virtual Device (AVD) or connect a physical testing device via Wi-Fi.
2. **APK Assembly & Installation**: Finalize `:app:assembleDebug` / release packaging and deploy to target.
3. **End-to-End Live Verification**: Validate cycle tracking, Room-to-Supabase bidirectional synchronization, biometric lock, payment gateway polling, and telemetry dispatches.
