# cylinder-gate (scaffold - not deployed)

Authorization center for the custom open-source AI project. Namespaced from
CycleCare: own tables (`cylinder_gate_*`), own function, own secrets.

## What it does
- Issues API keys (`ccai_...`), stores only their SHA-256 hash + prefix.
- Validates every request and enforces per-key per-minute and per-day limits
  atomically in Postgres (`cylinder_gate_consume` RPC - one round trip, fail-closed).
- Proxies allowed requests to the Cloudflare Workers AI endpoint
  (`CYLINDER_AI_URL` / `CYLINDER_AI_TOKEN` env), so upstream stays configurable
  until the model exists.

## Deploy (when Roy approves)
```
supabase db push                                   # applies 20261025120000_cylinder_gate.sql
supabase secrets set CYLINDER_GATE_ADMIN_SECRET=<random> \
  CYLINDER_AI_URL=<cloudflare run url> CYLINDER_AI_TOKEN=<token>
supabase functions deploy cylinder-gate
```

## Key lifecycle
- Create: `POST /cylinder-gate/admin/keys` with `x-gate-admin` -> returns the
  plaintext key ONCE. Distribute to an app/user; only the hash persists.
- List:   `GET /cylinder-gate/admin/keys` (ids, labels, limits, last use).
- Revoke: `POST /cylinder-gate/admin/revoke` `{key_id}` - instant, fail-closed.
- Client: `POST /cylinder-gate/v1/chat` with `x-cylinder-key` -> 401 unknown/
  revoked, 429 over limit, 503 until the upstream model is configured.

Rate-limit windows are fixed (minute + day), counted in `cylinder_gate_usage`.
