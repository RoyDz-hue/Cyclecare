export const MANIFEST_REVISION = "cyclecare-ai-manifest-v3";

// Keep this object server-owned. Unknown review/account/use-case states are closed.
export const PROVIDER_MANIFESTS = Object.freeze({
  COHERE: Object.freeze({
    id: "COHERE", display_name: "Cohere", model: "command-a-03-2025",
    policy_revision: "cohere-privacy-2026-03", policy_url: "https://cohere.com/privacy",
    processors: Object.freeze([{ id: "cohere", name: "Cohere", policy_url: "https://cohere.com/privacy" }]),
    account_reviewed: false, use_case_reviewed: false, audience_reviewed: false,
    eligible: false, unavailable_reason: "Cohere personal-data commercial/DPA eligibility has not been reviewed."
  }),
  GEMINI: Object.freeze({
    id: "GEMINI", display_name: "Google Gemini API", model: "gemini-2.5-flash",
    policy_revision: "gemini-api-terms-2026-03-23", policy_url: "https://ai.google.dev/gemini-api/terms",
    processors: Object.freeze([{ id: "google-gemini-api", name: "Google Gemini API", policy_url: "https://ai.google.dev/gemini-api/terms" }]),
    account_reviewed: false, use_case_reviewed: false, audience_reviewed: false,
    eligible: false, unavailable_reason: "Paid project, 18+ audience, region and non-medical-use eligibility has not been reviewed."
  }),
  OPENROUTER: Object.freeze({
    id: "OPENROUTER", display_name: "OpenRouter", model: "openai/gpt-4.1-mini",
    policy_revision: "openrouter-zdr-provider-policy-2026-03", policy_url: "https://openrouter.ai/docs/guides/privacy/provider-logging",
    processors: Object.freeze([{ id: "openrouter", name: "OpenRouter", policy_url: "https://openrouter.ai/docs/guides/privacy/provider-logging" }, { id: "pinned-upstream-openai-gpt-4.1-mini", name: "Pinned upstream: openai/gpt-4.1-mini", policy_url: "https://openrouter.ai/docs/guides/features/zdr" }]),
    upstream: "openai/gpt-4.1-mini", provider_only: "openai", allow_fallbacks: false, data_collection: "deny", zdr: true,
    account_reviewed: false, use_case_reviewed: false, audience_reviewed: false,
    eligible: false, unavailable_reason: "Pinned upstream, OpenRouter routing and ZDR eligibility have not been reviewed."
  })
});

export function canonicalManifest(manifest) {
  const ordered = {};
  for (const key of Object.keys(manifest).sort()) {
    const value = manifest[key];
    ordered[key] = Array.isArray(value) ? value.map((v) => canonicalManifest(v)) : (value && typeof value === "object" ? canonicalManifest(value) : value);
  }
  return ordered;
}
export function manifestJson(manifest) { return JSON.stringify(canonicalManifest(manifest)); }
export async function manifestFingerprint(manifest) {
  const bytes = new TextEncoder().encode(manifestJson(manifest));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
export async function manifestWithFingerprint(provider) {
  const manifest = PROVIDER_MANIFESTS[provider];
  if (!manifest) return null;
  return { ...manifest, manifest_fingerprint: await manifestFingerprint(manifest), manifest_revision: MANIFEST_REVISION };
}
