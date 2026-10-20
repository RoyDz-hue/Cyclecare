# CycleCare backend repairs and remaining release gates

The earlier blanket “not deployed” status in this document is superseded by `/workspace/cyclecare-fixes/approved-deployment/deployment-receipt.json`: the approved UUID-compatible repair, `ai-gateway` v12 and `image-generation` v5 were deployed. Payment handlers were not part of that rollout. Subsequent Admin/Android/backend alignment edits remain local and require separate rollout approval. Consult [deployment and operations](deployment_and_operations.md) for the current migration boundary.

## What the new Android client can do now

The app works locally, including a truly account-free first-use path. Online explanation and image creation first register explicit privacy choices through `set_my_privacy_consents`. If that RPC is absent or fails, they stay local or show an honest unavailable message. No provider key belongs in the APK.

An APK install alone does not deploy backend edits, configure provider credentials or establish that a signed-in remote flow works. Test compatibility with the approved baseline and separately approve any required follow-up rollout.

## AI request contract

`POST /functions/v1/ai-gateway` requires a valid bearer session and schema version 2:

```json
{
  "schema_version": 2,
  "user_question": "Question of 1–1200 characters",
  "context_mode": "minimal",
  "ai_consent": true,
  "conversation_history": [{"role": "user", "content": "A previously shared exchange"}]
}
```

Minimal mode must omit `context`. Expanded mode adds only `"context":{"summary":"User-visible summary, at most 2400 characters"}`. Up to six history messages are accepted, user/assistant roles only, each at most 600 characters. Android supplies up to three prior online exchanges, never a background health-database dump or raw saved notes.

Persisted AI consent is required for both modes. Premium is required by this staged gateway; membership in the server-only admin-role table bypasses the tier check, never consent or deterministic safety. The gateway does not read profiles, cycles or daily logs. Six bounded answer fields are required; malformed provider output is rejected rather than padded into a fake success. Structured validation is not clinical validation.

## Image request contract

```json
{"style":"botanical", "custom_prompt":"Optional decorative direction, at most 500 characters", "image_consent":true}
```

Allowed styles remain botanical, moonlit, sunrise and ocean. Premium/server-admin and persisted image consent are required. Android and the server bound the prompt; the server blocks common health, identity, contact and URL patterns. Free text cannot be perfectly classified: the app warns users not to include personal or health information, and never automatically attaches chat, notes, records or profile context. Pollinations provider/model selection is unchanged. No paid generation call was made.

The draft SQL atomically counts daily attempts with a locked per-user row. Protective defaults are 60 chat/3 image attempts for Premium and 300/30 for admins, using the database date. These are safety ceilings, not an approved subscription promise. Failed attempts can consume allowance. Owner approval, real concurrency tests, retention and operational monitoring remain required.

## Health synchronization is deliberately paused

Full-field v2 daily-log serialization and pagination are staged, but safe two-device revisions, conflict resolution, outbox and remote tombstone reconciliation are not complete. `SupabaseSyncService.conflictSafeProtocolReleased()` therefore returns false. Even with local consent and the new schema, this APK sends no health-sync upload/download. Consent-off and wiped-namespace paths also fail closed before session/network work.

`get_cyclecare_backend_contract(2)` identifies the draft schema, not release readiness for conflict-safe sync. Do not flip the Android release gate merely because this RPC exists. Complete and test the protocol first. Existing cloud records are not automatically imported into the new owner-scoped local namespaces.

## Historical drafts and the approved replacement

1. `20261018100000_cyclecare_backend_audit_repairs.sql`: earlier design for privacy consents, deletion evidence and quotas.
2. `20261018110000_cyclecare_backend_contract_v2.sql`: earlier design for log fields, v2 readiness, consent policies and health-only deletion.
3. `supabase/proposals/20261018120000_cyclecare_uuid_compatible_repairs.sql`: the reviewed UUID-compatible replacement, recorded as applied under migration version `20261018112033` and name `cyclecare_uuid_compatible_repairs_20261018` in the approved receipt.

Do not install the two historical drafts over the approved replacement or replay the numbered history. Already-applied files are not rewritten. Any new migration must be forward-only, reviewed against the current catalog and tested on representative schemas. PostgreSQL parsing alone does not validate execution, grants, RLS or concurrency. The receipt and prior UUID-review evidence record additional database tests; they are not evidence that subsequent edits are deployed or fully accepted.

## Deletion is health-only, not “delete everything”

`delete_my_cyclecare_health_data()` removes daily logs, cycles and privacy consent; scrubs health-bearing profile fields while preserving administrative restrictions; disables sync; and records a server-side no-resurrection tombstone. Stale consent requests cannot turn cloud sync back on for a tombstoned identity. Any future resume workflow needs explicit user intent and a tested new protocol.

The Android Settings action is distinct from local erasure, requires confirmation, and reports success only after the authenticated RPC confirms it. Local records remain after cloud-copy deletion. Schema-required 28-day/5-day profile defaults after scrubbing are neutral placeholders, not retained personal measurements.

Auth identity, payments, economy/asset ownership, provider-side AI records and audit evidence are not erased by this action. Retention policy, complete account closure and processor deletion remain owner/privacy decisions, not legal-compliance claims.

## Payments remain blocked, not falsely “fixed”

PayHero initiation returns an authenticated unavailable response before any provider request or transaction insertion. Its callback verifies the existing transaction capability token but cannot settle any pending payment from caller-reported status, including string `ResultCode`, `success:true` or conflicting fields. It does not credit or finalize pending transactions. Already-recorded successes remain idempotent. Existing pending payments need an approved independent reconciliation process; do not retry charges to discover their status.

PayPal verification code is unchanged and still requires sandbox lifecycle testing. OKX/PayNexus remain unavailable. An immutable economy ledger, subscription/entitlement lifecycle, refunds/reversals, settlement-reference uniqueness and reconciliation are not implemented by this patch.

## Validation boundaries

Offline Node contract and real callback-handler fixtures exercise request validation and fail-closed local payment behavior. Deno checks syntax/types, not clinical safety or real provider behavior. Android tests exercise consent, owner database switching, no-network sync gates, safety and the chat UI. The current alignment pass changes only local source; the separately approved earlier deployment is described above. No current production provider switch, PWA publication, paid generation or real payment is authorized by the local task.
