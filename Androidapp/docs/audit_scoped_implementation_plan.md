# Audit-Scoped Implementation Plan & Fulfillment Strategy

**Document Version:** 2.0  
**Target Execution Phase:** Post-Audit Code Optimization & Refactoring  
**References:** `/docs/audit_report.md`, Master Architecture v2.0, PRD v2.0  

---

## Executive Summary & Fulfillment Goals

This implementation plan defines the exact fulfillment strategy to resolve all identified gaps, enforce the **Midnight Local Time Reset per Calendar Day** claim restriction, integrate cryptographic webhook signature verification in Supabase Edge Functions, and enhance local-first database handshakes.

---

## 1. Daily Mining & Claiming Enforcement (Calendar Day Midnight Reset)

### Target Rules
- A user can claim daily coin rewards **only once per calendar day** (`LocalDate.now()`).
- Attempting to claim again on the same calendar day returns a clear message: `"You have already claimed your daily attune reward today. Returns at midnight."`

### Required Changes
1. **`EconomyProfile` model (`EconomyModels.kt`)**:
   - Add field `lastMinedDateString: String? = null` (e.g., `"2026-10-10"`).
2. **`SupabaseEconomyService.kt`**:
   - In `verifyAndMineCoins()`, compare `currentProfile.lastMinedDateString` with `LocalDate.now().toString()`.
   - If equal, reject claim with message `"Daily attune reward already claimed today."`.
3. **`CycleCareViewModel.kt`**:
   - Enforce midnight reset check before calling mining endpoint.

---

## 2. Payment Edge Function Webhook Signature Validators

### Target Rules
- All payment webhooks (`payhero-callback`, `paypal-capture`, `okx-webhook`) must perform authentic signature/hash verification and update transaction status idempotently in Postgres `public.transactions`.

### Edge Function Implementations
1. **`payhero-callback/index.ts`**:
   - Validates callback JSON payload (`external_reference`, `amount`, `status`).
   - Updates `transactions` table status to `success` or `failed` using `SUPABASE_SERVICE_ROLE_KEY`.
2. **`okx-webhook/index.ts`**:
   - Computes HMAC-SHA256 signature using `OKX_WEBHOOK_SECRET` over request timestamp + body.
   - Rejects unauthenticated requests with HTTP 401.
3. **`paypal-capture/index.ts`**:
   - Executes PayPal order capture call to PayPal API (`api-m.paypal.com`) and verifies status is `COMPLETED`.

---

## 3. Local DB Handshake & Sync Optimization

### Target Rules
- Ensure `SupabaseSyncService.kt` performs bidirectional delta handshake (pushing local modifications to Supabase and pulling remote updates into Room DB).

---

## 4. Codebase Modularization Strategy

### ViewModel Modularization
- Decouple payment and mining state management into dedicated UseCases (`MiningUseCase`, `PaymentUseCase`) to ensure `CycleCareViewModel` remains lightweight and scalable.
