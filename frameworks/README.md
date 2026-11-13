# CycleCare frameworks and recipes

Roy's guiding frameworks: the recipes and formulas for writing good CycleCare
code. This directory is **developer guidance only** - nothing here is bundled
or compiled into the app, the backend bundles or the Admin PWA. Runtime
algorithms the app actually calls stay compiled in their own modules; this is
their documented home and index, not their build location.

| Framework | Intent | Status | Implementation / reference |
|---|---|---|---|
| [AI core](AI_CORE.md) | One voice, wisdom filters, versioned prompt registry and run audit for every CycleCare agent | Live (backend) | `Androidapp/supabase/functions/_shared/ai-core.ts`, used by `ai-gateway`, `community-pulse`, `admin-command` |
| Persona memory | Per-member memory of persona preferences, size-capped, service-role only (the migration's own wording is 'recursive learning'). Memory/context storage, not model training or a self-improving algorithm | Live (backend) | `Androidapp/supabase/migrations/20261021160000_community_personas.sql` + `Androidapp/supabase/functions/community-pulse/index.ts` |
| Payment routing registry | Maps each UI payment option to its real provider, allowed regions and charged currency; fails closed | Live (backend) | `Androidapp/supabase/functions/_shared/payment-routing.ts` + `Androidapp/supabase/migrations/20261021200000_payment_routing_registry.sql` |
| Tone language engine | Phase-aware greetings and confidence wording per tone preference (warm, calm, direct) | Preserved, not yet wired | [android-tone-language/LanguageEngine.kt](android-tone-language/LanguageEngine.kt) - reference copy, kept out of the app source set until a surface uses it |
| Guide humanisation | Warmth rules for community Guide messages: no promo pressure, no clinical claims, no provider names | Live (backend) | prompt versions + wisdom filters in `_shared/ai-core.ts` and `community-pulse/index.ts` |

Terminology: Roy speaks of his learning-center and self-recursive frameworks.
The exact mapping of those terms to the implementations above is unconfirmed;
this index links what exists and quotes source wording rather than defining
his terms for him. If his source specs for them surface, preserve them here.

Rules for this directory:

- Docs and reference code here must never move into `Androidapp/app/src`,
  assets, resources, edge-function bundles or the Admin PWA bundle. If a
  surface needs one of these algorithms, copy it into the proper module and
  wire it deliberately.
- Keep exactly one canonical copy of each framework here; delete duplicates
  rather than maintaining parallel versions.
- Distinguish live implementations from plans. Nothing in this index claims a
  capability that is not implemented at the linked location.
