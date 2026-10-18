# CycleCare System Bug Fixes, Architecture Specification & Supabase Deployment

## Overview
This document serves as the master engineering record for the bug fixes, architectural redesigns, and backend deployment completed across the **CycleCare Android Mobile Application**, **CycleCare Admin Dashboard (PWA)**, and **Supabase Cloud Infrastructure**.

---

## 1. Architectural Bug Fixes & Implementations

### Requirement 1: Balance Synchronization & Administrative Grants
* **Root Cause Identified**:
  In the Admin PWA (`admin-pwa/src/components/UserManagement.jsx`), coin granting and debiting relied on `PATCH /rest/v1/economy_profiles?id=eq.${selectedUser.econId}`. When a user did not have a predefined `econId` (common for newly registered or anonymous accounts), the request silently failed. Furthermore, notifications omitted the `user_id` field.
* **Architecture Fix**:
  - **Upsert by `user_id`**: Updated `UserManagement.jsx` to perform `POST /rest/v1/economy_profiles?on_conflict=user_id` with `Prefer: resolution=merge-duplicates`, simultaneously updating `coin_balance`, `lifetime_earned`, and `updated_at`.
  - **Dual Notification Fields**: Added both `user_id` and `target_identifier` in dispatched administrative records so the mobile app immediately catches updates.
  - **Offline Handshake & State Reconciliation**:
    - Speculative offline claims record `pendingOfflineClaimDate` and `pendingOfflineClaimAmount` inside `AuthPreferences`.
    - Implemented `reconcilePendingOfflineClaims()` in `SupabaseEconomyService.kt` and `MiningUseCase.kt`.
    - Added an automatic network listener via `NetworkHelper.observeNetwork(context)` in `CycleCareViewModel.kt`. When connectivity is restored, the client performs a secure server handshake to commit or reconcile the speculative balance.

---

### Requirement 2: Messaging & Notification Infrastructure
* **Root Cause Identified**:
  - The Admin PWA was saving rows with only `target_identifier`, whereas mobile app queries originally looked up `user_id`, creating a recipient schema mismatch.
  - Push notifications experienced significant delivery latency due to passive, infrequent sync cycles.
* **Architecture Fix**:
  - In `InHouseMessaging.jsx` and `UserManagement.jsx`, notification payloads now write both `user_id` and `target_identifier`.
  - For global broadcasts (`ALL`), the PWA writes to both `broadcast_dispatches` and `user_notifications` with `target_identifier: 'ALL'`.
  - In `SupabaseNotificationService.kt`, query filtering queries aliases: `or=(user_id.eq.$id,target_identifier.eq.$id,user_id.eq.ALL,target_identifier.eq.ALL)` across `effectiveUserId`, `deviceId`, and `userEmail`.
  - In `CycleCareViewModel.kt`:
    1. Added an immediate trigger on network reconnection.
    2. Added a 25-second active background poller loop when online, removing notification lag.

---

### Requirement 3: Payment Gateway & Transactional Integrity
* **Root Cause Identified**:
  - In `CycleCareViewModel.kt`, `processUnifiedMpesa` had a hardcoded `delay(2000)` that instantly added coins to the local balance without verifying if the user entered their PIN.
  - Payment services in `SupabasePaymentService.kt` and `MpesaPaymentService.kt` defaulted to `isSuccess = true` in exception/error blocks.
  - No client-side input validation existed for phone numbers and card details.
* **Architecture Fix**:
  - **Strict Input Validation**: Created `PaymentInputValidator.kt`:
    - **Kenyan Phone Validation**: Supports `07XX`, `01XX`, `+2547XX`, and `+2541XX`, standardizing to `2547XXXXXXXX`.
    - **Card Numbers**: Enforces **Luhn Mod-10 algorithm** and 13–19 numeric digit lengths.
    - **Expiration Dates**: Validates `MM/YY` format, month 1–12, and ensures the expiration is strictly in the future.
    - **CVV**: Validates 3 or 4 numeric digits.
  - **Inline Validation Feedback**: Integrated real-time error hints in `UnifiedGamifiedCheckoutDialog.kt`.
  - **Elimination of False-Positive Payouts**:
    - Removed all fake `isSuccess = true` fallbacks in error handlers.
    - `processUnifiedMpesa` now initiates the transaction, extracts the external reference, and invokes `pollAndCompletePayment(extRef, timeoutSeconds = 60L)`. Coins are credited only after the gateway callback confirms successful payment.

---

### Requirement 4: Offline Mode & Network Constraints
* **Enforced Online Workflows**:
  - **AI Clinical Processing (`consultCare`)**: Checked against `NetworkHelper.isOnline`. If offline, immediately returns a structured `CareResponse` indicating that AI clinical analysis requires active connectivity.
  - **USP Community Circles (`GuidedChatsScreen.kt`)**: Displays an offline warning card and disables message input and sending while offline.
  - **Peer Coin Transfers (`transferCoins`)**: Enforces active internet and rejects offline transfers.
  - **Real-Money Payments**: `UnifiedGamifiedCheckoutDialog.kt` and payment methods (`processUnifiedMpesa`, `processUnifiedCard`, `processUnifiedPaypal`, `processUnifiedCrypto`) check network status and refuse execution when offline.
* **Offline Mining Policy**:
  - Restricts offline claiming to a maximum of one speculative claim per calendar day.

---

### Requirement 5: Admin User Management Enforcement (Ban / Suspend)
* **Root Cause Identified**:
  - Admin PWA only updated the `profiles` table. The Android app read `economy_profiles`, and in offline or error states defaulted to `status = "ACTIVE"`.
  - A banned user could bypass the restriction simply by turning off their mobile data or launching the app in airplane mode.
* **Architecture Fix**:
  - **PWA Multi-Table Sync**: `UserManagement.jsx` updates `profiles` (by `id` and `user_id`) **and** upserts `economy_profiles?on_conflict=user_id` with `status`, `suspension_reason`, and `suspended_until`.
  - **Persistent Restriction Caching**: `SupabaseEconomyService.kt` writes the ban/suspension status to `AuthPreferences`.
  - **Startup Enforcement**: `CycleCareViewModel.kt` reads `isBanned` and `isSuspended` directly from cached `AuthPreferences` on startup before any network calls, ensuring users **cannot** bypass account restrictions by going offline.

---

## 2. Supabase Live Deployment

### Database Tables (PostgREST Endpoints — Verified HTTP 200)

| Table Name | Endpoint | RLS Status | Description |
| :--- | :--- | :--- | :--- |
| `transactions` | `/rest/v1/transactions` | Enabled | Unified payment history (PayHero, PayPal, OKX) |
| `broadcast_dispatches` | `/rest/v1/broadcast_dispatches` | Enabled | Global administrative launch alerts & announcements |
| `user_notifications` | `/rest/v1/user_notifications` | Enabled | User-targeted notifications, rewards, and security notices |
| `economy_profiles` | `/rest/v1/economy_profiles` | Enabled | Coin balances, tiers, ranks, artifacts, and ban/suspension status |
| `mining_records` | `/rest/v1/mining_records` | Enabled | Calendar-day claim tracking (`UNIQUE(user_id, claimed_date)`) |
| `user_artifacts` | `/rest/v1/user_artifacts` | Enabled | Unlocked frames and 3D collectible artifacts |
| `profiles` | `/rest/v1/profiles` | Enabled | Core user accounts and onboarding profiles |

### Deployed Edge Functions (Deno Runtime — Verified ACTIVE)

| Function | Endpoint | Version | Responsibility |
| :--- | :--- | :--- | :--- |
| **`ai-gateway`** | `.../functions/v1/ai-gateway` | `v2` | Multi-intent routing & clinical AI generation |
| **`payhero-stk`** | `.../functions/v1/payhero-stk` | `v1` | Initiates M-Pesa STK push via PayHero API |
| **`payhero-callback`** | `.../functions/v1/payhero-callback` | `v1` | Handles M-Pesa webhooks & idempotent coin crediting |
| **`paypal-create-order`**| `.../functions/v1/paypal-create-order` | `v1` | Creates verified PayPal orders |
| **`paypal-capture`** | `.../functions/v1/paypal-capture` | `v1` | Captures PayPal & Card charges |
| **`okx-create`** | `.../functions/v1/okx-create` | `v1` | Generates OKX cryptocurrency deposit orders |
| **`okx-webhook`** | `.../functions/v1/okx-webhook` | `v1` | Processes OKX deposit confirmations |

### Atomic RPC Functions Installed
1. **`verify_and_mine_coins(p_user_id, p_base_reward)`**: Atomically awards coins with tier multipliers, ranks, and calendar-day locking.
2. **`transfer_coins_atomic(p_sender_id, p_recipient_email, p_amount)`**: Safely transfers coins between users with balance verification.
3. **`grant_artifact(p_user_id, p_artifact_id, p_title, p_description)`**: Allows admins to grant artifacts and dispatches an official notification to the user.

---

## 3. Verification & Build Integrity

* **Android Application**:
  Command: `./gradlew compileDebugKotlin`
  Status: **BUILD SUCCESSFUL (Exit Code 0)**
* **Admin Dashboard PWA**:
  Command: `npm --prefix admin-pwa run build`
  Status: **BUILD SUCCESSFUL (Exit Code 0)**
* **Supabase Live PostgREST API**:
  Verified: All 7 core tables responding with `HTTP 200`.
