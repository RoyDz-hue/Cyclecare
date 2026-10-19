# CycleCare Android and backend

CycleCare provides local menstrual-health tracking, cycle-pattern estimates and optional server-routed health information. It is not a diagnostic tool, contraception method or substitute for a clinician.

This repository contains the Android application (`app/`) and Supabase source (`supabase/`). The administrator PWA has its own repository: [Cyclecare-admin](https://github.com/RoyDz-hue/Cyclecare-admin). Build and hosting instructions for that PWA belong there; this Android repository is not a Netlify web application.

## Build and test Android

Use JDK/Android SDK support for API 36. Copy `.env.android.example` to `.env.android` and configure only the public Supabase URL and anon/publishable key. Never include provider, service-role, payment or signing secrets in Android configuration.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# With an isolated Android device/emulator connected:
./gradlew :app:connectedDebugAndroidTest
```

Debug builds use a separate application/data namespace and are not store releases. An approved private upload keystore and signing credentials are required for a release. Local health databases are owner-scoped and SQLCipher-encrypted with an Android-Keystore-wrapped random passphrase. Android backup/device transfer of private app data is disabled.

## Test the backend offline

Node 24 can execute the TypeScript helper tests without transpiling a copied implementation:

```bash
node --test supabase/functions/_shared/*.test.mjs
```

SQL tests in `supabase/tests/` are for fresh, disposable PostgreSQL databases only. Never run their fixture/bootstrap scripts on a real user database.

## Release boundaries

Admin selects Cohere, Gemini or OpenRouter on the server. Android does not choose an arbitrary cloud provider. Pollinations is image-only. Credential presence does not prove provider health or generation availability.

Cloud sync, multi-provider Hybrid and payment checkout remain disabled. The feature branch now includes a durable Room v8 outbox and conflict UI, v3-only sync transport and unified health erasure, processor-specific Android consent and pinned server routing, plus verified payment reconciliation and credit/refund/reversal accounting. These are candidate integrations, not deployed or clinically approved releases. Physical-device/two-device staging acceptance, provider account-policy review, PayPal-hosted sandbox acceptance and qualified clinical sign-off remain required. Database or environment flags alone cannot release source-gated features.

The files under `supabase/proposals/` are not applied production migrations. Do not replay the migration history or run a blanket `supabase db push` against the existing project. Source commits, successful builds and local SQL assertions neither deploy changes nor establish production/clinical acceptance.

See [deployment and operations](docs/deployment_and_operations.md), [clinical acceptance](docs/clinical_acceptance.md), and [debug diagnostics privacy](docs/remote_debug_architecture.md). Production deployment and merging to `main` require separate approval.

## Optional developer diagnostics

Normal debug builds do not embed a temporary developer service URL. Diagnostic pairing/sending is unavailable unless an approved HTTPS origin is explicitly supplied for that build:

```bash
./gradlew :app:assembleDebug -Pcyclecare.debugBridgeUrl=https://diagnostics.example.test
```

Use your own authorized temporary origin, not the example. Credentials, paths, queries and fragments are rejected. Release builds remain unconfigured and runtime-disabled regardless of this property. A configured endpoint does not replace the owner's per-session pairing and snapshot consent. See [debug diagnostics privacy](docs/remote_debug_architecture.md).
