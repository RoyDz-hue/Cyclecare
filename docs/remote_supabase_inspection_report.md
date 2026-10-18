# Remote Supabase Live Inspection & Architectural Gap Analysis Report

**Project Reference:** `apjqcocxtylsofgxhetb`  
**Endpoint:** `https://apjqcocxtylsofgxhetb.supabase.co/rest/v1`  
**Date:** October 10, 2026  
**Inspector:** Lead Cloud Architect & Security Engineer  

---

## 1. Executive Summary & Live Probing Methodology

A live, direct REST and RPC query inspection was performed on the production Supabase backend instance associated with the **CycleCare** system. The inspection probed all database tables, RPC procedures, administrative configurations, authentication contracts, payment records, and economy profiles using the project's standard credentials (`SUPABASE_URL` and `SUPABASE_ANON_KEY`).

### Key Findings Matrix

| Component / Endpoint | Status | HTTP Code | Schema / Structure Notes |
| :--- | :--- | :--- | :--- |
| `public.profiles` | **Active** | 200 OK | Contains user identity, device ID, account mode, cycle settings, status (`ACTIVE`, `SUSPENDED`, `BANNED`), and role. |
| `public.cycles` | **Active** | 200 OK | Stores period & cycle history records per user. |
| `public.daily_logs` | **Active** | 200 OK | Stores daily symptom, mood, and health logs. |
| `public.user_notifications` | **Active** | 200 OK | Stores targeted dispatches and system alerts. |
| `public.app_config` | **Active** | 200 OK | Stores system-wide configuration keys (e.g. `payment_gateway`). |
| `public.economy_profiles` | **Active** | 200 OK | Stores coin balance, lifetime earnings, tier, rank, active themes, and penalization timestamps. |
| `public.transactions` | **MISSING** | 404 (PGRST205) | **Critical Gap**: Payment table does not exist in remote Supabase schema. |
| `public.broadcast_dispatches` | **MISSING** | 404 (PGRST205) | **Critical Gap**: Official admin launch dispatches table missing. |
| `public.user_artifacts` | **MISSING** | 404 (PGRST205) | **Critical Gap**: Dedicated user artifact inventory table missing. |
| `public.mining_records` | **MISSING** | 404 (PGRST205) | **Critical Gap**: Calendar-day mining claim enforcement table missing. |

---

## 2. Remote OpenAPI REST Schema & Endpoints Audit

### 2.1 Active Tables Schema Structure

#### A. `public.profiles`
- **Columns**: `id` (uuid), `user_id` (text), `device_id` (uuid), `account_mode` (text), `goals` (text), `last_period_date` (text), `cycle_length` (int), `period_duration` (int), `typical_symptoms` (text), `typical_energy` (text), `typical_sleep` (text), `created_at` (timestamptz), `updated_at` (timestamptz), `status` (text), `suspension_reason` (text), `suspended_until` (timestamptz), `role` (text).
- **Assessment**: Properly configured for account restriction enforcement (`ACTIVE`, `SUSPENDED`, `BANNED`).

#### B. `public.economy_profiles`
- **Columns**: `id` (uuid), `user_id` (text), `coin_balance` (numeric), `lifetime_earned` (numeric), `lifetime_spent` (numeric), `tier` (text), `rank` (text), `active_frame` (text), `active_artifact` (text), `active_theme` (text), `unlocked_items` (jsonb), `created_at` (timestamptz), `updated_at` (timestamptz), `status` (text), `suspension_reason` (text), `suspended_until` (timestamptz), `artifacts` (jsonb), `last_penalized_at` (timestamptz).
- **Assessment**: Active and tracks core balance, but lacks an atomic `last_mined_date` column (`YYYY-MM-DD`) for calendar day midnight reset enforcement.

#### C. `public.app_config`
- **Key-Value State**:
  ```json
  {
    "key": "payment_gateway",
    "value": {
      "okxEnabled": true,
      "sandboxMode": false,
      "mpesaEnabled": true,
      "minDepositKes": 49,
      "autoVerifyWebhooks": true,
      "activeMpesaProvider": "PAYHERO"
    }
  }
  ```
- **Assessment**: Fully aligned with the Admin PWA toggle requirements.

---

## 3. RPC Functions Audit

Live POST queries to `/rest/v1/rpc/*` returned code `PGRST202` (Function not found in schema cache) for all core business functions:
1. `public.verify_and_mine_coins` - **MISSING**
2. `public.get_user_notifications` - **MISSING**
3. `public.claim_daily_attune` - **MISSING**
4. `public.execute_admin_dispatch` - **MISSING**
5. `public.grant_artifact` - **MISSING**

---

## 4. Security & Row Level Security (RLS) Rules Analysis

- **Public Access**: Tables `profiles`, `economy_profiles`, `cycles`, `daily_logs`, `user_notifications`, and `app_config` allow anonymous key reads, subject to `user_id` filters.
- **Service Role Bypass**: Deno Edge Functions and Admin Webhooks write using `SUPABASE_SERVICE_ROLE_KEY` to bypass RLS when performing payment status updates, account suspensions, and broadcast dispatches.

---

## 5. Identified Gaps & Missing Database Contracts

1. **Gap 1: Missing Payment Transactions Schema (`public.transactions`)**
   - The Android client and payment Edge Functions (`payhero-stk`, `paypal-create-order`, `okx-create`) require `public.transactions` to store payment attempts, external references, amounts, and statuses (`pending`, `success`, `failed`, `cancelled`, `expired`).

2. **Gap 2: Missing Broadcast Dispatches Schema (`public.broadcast_dispatches`)**
   - Required for official admin launch banners and global broadcast announcements.

3. **Gap 3: Missing Dedicated Daily Mining Claim Records (`public.mining_records`)**
   - Needed to enforce strict **Midnight Local Time Reset per Calendar Day** (`claimed_date = 'YYYY-MM-DD'`) to ensure a user can claim coins **only once per calendar day**.

4. **Gap 4: Missing User Artifact Inventory (`public.user_artifacts`)**
   - Required for tracking gifted artifacts, unlocked items, and coin rewards.

5. **Gap 5: Missing Stored RPC Procedures**
   - Missing atomic RPC procedures in Postgres for mining validation, artifact granting, and admin dispatches.

---

## 6. Fulfillment & Patch Generation Strategy

To address all identified gaps, the following artifacts will be created and synchronized with the repository:
1. **SQL Patch Migration Script**: `supabase/full_schema_migration.sql` containing all table definitions, indexes, RLS policies, and stored procedures.
2. **Deno Edge Functions Security & Webhook Updates**: Hardening webhook signature verification in `payhero-callback`, `paypal-capture`, and `okx-webhook`.
3. **Android Client Synchronization**: Aligning local database models, repository services, and ViewModels with the remote contracts.
