# CycleCare

This `/workspace/CYCLECARE/CYCLECARE` checkout is the **canonical v6 project**. The pre-v6 snapshot is retained separately for reference and rollback; v6's redesigned Android and admin interfaces remain the primary UI.

CycleCare is an Android menstrual-health companion with cycle and daily symptom logging, cycle-pattern estimates, an optional server-routed health-information assistant, notifications, a server-authoritative coin economy, and a protected administrator web app.

## Project areas

- `app/` — Android/Compose client and local Room health database.
- `supabase/` — ordered database migrations and Edge Functions for authentication-protected AI, economy, and payment operations.
- `admin-pwa/` — React/Vite administrator PWA. It requires a signed-in user who has been provisioned as a CycleCare administrator in the database.
- `docs/` — security audit, deployment checklist, and provider verification notes.

## Build the Android app

For the debug-only phone-to-sandbox diagnostics companion and its privacy limits, see [remote debug architecture](docs/remote_debug_architecture.md).

1. Use Android Studio with a compatible JDK and Android SDK for compile/target API 36.
2. Copy `.env.android.example` to `.env.android` and set only `SUPABASE_URL` and the public `SUPABASE_ANON_KEY`. Never put a service-role, PayHero, PayPal, or AI key in the Android environment file.
3. From the repository root, run:

   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```

A release build additionally needs a private upload keystore and the `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` environment variables. Do not commit the keystore or passwords.

On first database open, the Android app stores a random SQLCipher passphrase wrapped by Android Keystore, converts an existing plaintext Room database into a temporary encrypted copy, verifies it, and only then replaces the original. Android cloud backup and device-transfer backup are disabled for private app data. See [local data protection and deployment](docs/deployment_and_operations.md).

## Build the administrator PWA

```bash
cd admin-pwa
cp .env.example .env.local
# Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY in .env.local
npm ci
npm run build
```

Only the public Supabase URL and anon key belong in Vite configuration. PWA management calls use the signed-in administrator's access token and database role checks. The root `netlify.toml` is the single canonical Netlify build configuration and sets `base = "admin-pwa"` explicitly.

The Android app currently routes AI through the authenticated Supabase `ai-gateway`; Firebase SDK features are not used. No `google-services.json` is required unless a future Firebase-backed feature is deliberately added.

## Supabase setup

The existing production project has migration-history drift. **Do not replay every local migration or run a blanket `supabase db push` against it.** The approved receipt records the UUID-compatible repair, `ai-gateway` v12 and `image-generation` v5. Later local edits are not deployed. Review [deployment and operations](docs/deployment_and_operations.md), compare the real catalog/history and validate any new forward-only migration on a disposable baseline before requesting rollout approval.

The prior root-level `supabase/full_schema_migration.sql` and `supabase/supabase_transactions_schema.sql` scripts are intentionally disabled: they contain legacy policy/function definitions. Do not apply them. The authoritative JWT settings are in `supabase/config.toml`.

Edge Functions need server-side secrets configured before deployment. The current provider and migration prerequisites, first-admin provisioning step, and verification checklist are in [docs/deployment_and_operations.md](docs/deployment_and_operations.md).

## Current checkout behavior

- The server owns the supported coin packages, prices, and account credits.
- Local PayHero/M-Pesa initiation and settlement fail closed pending independent verification. The latest approved rollout did not update production payment functions; this local gate must not be assumed deployed. PayPal still needs sandbox lifecycle verification.
- Direct card entry and the scaffolded OKX/crypto path are deliberately disabled until their server/provider integrations are complete.
- Provider sandbox credentials and callback testing are required before production checkout is enabled.
- Admin selects Cohere, Gemini or OpenRouter on the server for Android chat. Hybrid/multi-provider routing is unavailable under the current contract; Pollinations is image-only. Credential presence is not proof of provider health.
- Health cloud sync remains release-gated until consent/revision-safe two-device behavior is complete. Essential local tracking and deterministic safety do not depend on cloud AI or payment.

CycleCare offers health information and tracking, not diagnosis or a substitute for a clinician. Cycle estimates are informational and become more useful as the user records more cycle starts.
