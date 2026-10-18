# Deployment and operations

## Current baseline and approval boundary

The approved deployment receipt at `/workspace/cyclecare-fixes/approved-deployment/deployment-receipt.json` records migration `cyclecare_uuid_compatible_repairs_20261018` (version `20261018112033`), `ai-gateway` v12 and `image-generation` v5 on project `apjqcocxtylsofgxhetb`. Both functions have JWT verification enabled. The receipt is historical evidence, not a live health check or permission to deploy subsequent edits.

Admin/Android alignment and inspection fixes are reviewed source changes for the separately approved GitHub feature branches, not a production rollout. They do not change production provider selection, secrets, payments or hosting. Publication requires separate approval. Local tests do not establish signed-in production acceptance, clinical validity or provider-generation success.

## Database migrations: do not replay the local history

Local migration filenames and production migration history differ. Do **not** run a blanket `supabase db push` against the existing project or replay all timestamped files. The two earlier audit migrations in `supabase/migrations/` are design history, not instructions to install them over the approved UUID-compatible repair. The reviewed SQL retained at `supabase/proposals/20261018120000_cyclecare_uuid_compatible_repairs.sql` corresponds to the separately recorded approved repair; its filename does not match the applied migration version.

Before any future schema change, compare the actual production catalog and applied migration history with the approved receipt, test a new forward-only migration on a disposable representative baseline, review grants/RLS/function bodies and concurrency behavior, then obtain rollout approval for that exact candidate. Never rewrite already-applied migration history. The old full-schema and transaction-schema scripts remain disabled and must not be applied.

Administrator membership comes from the protected `cyclecare_admin_roles` table, never a profile role supplied by a client. Provision only an explicitly approved Auth identity; never grant the role to a public/shared account. Existing admin membership remains broad: action-level roles and session assurance are unfinished.

## AI provider control

The supported **chat** providers are Cohere, Gemini and OpenRouter. The Admin PWA writes the singleton server runtime configuration through an authenticated, admin-authorized RPC. Android calls `ai-gateway` and does not select a cloud provider. Missing or unsupported configuration must fail closed, not silently choose a default.

Hybrid/multi-provider chat is not part of the deployed database contract. Its Admin option is unavailable until routing, provider-specific processing consent, bounded cost/failure handling and compatibility tests are reviewed and approved. There is no automatic cross-provider fallback. Android's deterministic local safety and local educational guidance remain independent of the selected remote provider; they are not evidence that multi-provider Hybrid is enabled.

Pollinations is **image-only**, not a selectable chat provider. AI status based on secret presence means configured, not authenticated, model-compatible or healthy. Prior Cohere/OpenRouter read-only key checks do not establish generation availability. Gemini previously returned an invalid-key error; no repaired key or successful Gemini generation is implied.

Keep credentials only in server-side Edge secrets:

| Purpose | Secret/config names |
|---|---|
| Chat | `COHERE_API_KEY`, `COHERE_MODEL`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `OPENROUTER_API_KEY`, `OPENROUTER_MODEL` |
| Images | `POLLINATIONS_API_KEY` and the image handler's approved model configuration |
| Supabase | `SUPABASE_URL`, public anon/publishable key and server-only service-role/secret key |
| PayPal | `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_MODE`, `PAYPAL_RETURN_URL`, `PAYPAL_CANCEL_URL` |

Never put provider, service-role, payment, PAT or signing credentials in Android, Vite environment variables, source files or logs. JWT settings in `supabase/config.toml` are part of the boundary. Do not weaken them to work around an integration problem.

## Android and Admin builds

Android requires JDK/Android SDK support for API 36. Only the public Supabase URL and anon key belong in `.env.android`; see `.env.android.example`.

Core-library desugaring preserves `java.time` compatibility with the declared minimum Android API 24; do not remove it or suppress NewApi errors to make lint pass. Explicit backend URL/key settings must validate as public-client configuration. Invalid or partial settings disable the connection instead of redirecting requests to production. A fully absent configuration retains the documented production default. Accepted staging URLs remain bound to their supplied key; legacy JWT project references are checked when present, while opaque publishable-key ownership must be verified by the selected project's authentication service.

```bash
./gradlew testDebugUnitTest assembleDebug
cd admin-pwa
npm ci
npm test
npm run build
```

A debug APK is not a store release. A release requires an approved private upload keystore, signing configuration and actual device acceptance. The encrypted local database uses an Android-Keystore-wrapped random SQLCipher passphrase. Account namespaces, legacy-data quarantine, guest-to-account transitions and upgrade recovery still need device tests. Android backup and device transfer are disabled for private app data.

Admin builds use only `VITE_SUPABASE_URL` and the public `VITE_SUPABASE_ANON_KEY`. A signed-in administrator's token authorizes management requests. Building `dist/` does not publish it. The root `netlify.toml` is the current combined-source build configuration; separate repository hosting must be reviewed against the intended repository root before publication.

The standalone Admin repository uses the `admin-pwa/netlify.toml` source file at its repository root: no nested base directory, build `npm run build`, publish `dist`. The combined tree's outer configuration retains `base = "admin-pwa"`. Keep hosting dashboard overrides consistent with the selected layout. Run Admin session-refresh and provider-save concurrency regressions before publishing; a valid access-token rotation is not a new user session, while logout/relogin must invalidate outstanding operations.

## Health sync and deletion

Health synchronization remains release-gated. Even with user consent and backend schema version 2, the Android client must not upload/download health records until owner-bound revisions, operation IDs/outbox, conflicts, tombstones, consent revocation and recovery are implemented and tested. A schema readiness marker does not release this gate.

Local erasure and `delete_my_cyclecare_health_data()` are distinct. Cloud-health deletion preserves local records and retained account/payment/economy/audit evidence, disables health sync and records a no-resurrection tombstone. It is not full account deletion or provider-side AI erasure. A safe resume workflow and retention policy remain unfinished.

## Payments and economy remain gated

The **local** PayHero initiation/callback code fails closed pending independent settlement verification. The latest receipt explicitly says payment functions were not modified by that approved rollout; do not assume production already has the local PayHero gate. Check this difference in a separately approved payment review before any production checkout. Do not retry a charge to discover the status of an ambiguous payment.

PayPal requires sandbox lifecycle tests. OKX/PayNexus and direct card processing remain unavailable. Immutable economy events, unique settlement references, reconciliation, refunds/reversals and authoritative subscription expiry/revocation are not completed. Package metadata or an economy tier field is not proof of a finished subscription system. No real payment tests are authorized by this local task.

## Release acceptance

Before separately approved publication, test ordinary users and admins with real authenticated staging sessions; denied/revoked consent; unavailable/invalid provider credentials and output; provider changes while requests are in flight; account switching; safety parity offline/online; no chat-derived writes before confirmation; and actual device accessibility/keyboard behavior. Verify the reviewed migrations and RLS on representative legacy schemas and test genuine concurrent operations. Clinical escalation/content and processor retention require qualified review.

Do not interpret passing unit tests, a successful debug build or an old production receipt as closure of these remaining release blockers.
