// Single declarative capability spec per ACI tool. One source for the
// planner catalog, review-card content, clarification essentials and
// verification requirements, so a new capability is one definition
// instead of edits in five places. Contract tests enforce completeness.

export type ToolKind = "READ" | "WRITE" | "RISKY";

export interface ToolSpec {
  name: string;
  kind: ToolKind;
  /** Exact planner-catalog line (keep wording stable; the planner reads it). */
  catalogLine: string;
  /** Args the admin must supply; absence is the ONLY reason to clarify. */
  essentialArgs: string[];
  /** Bound args the review card must show before Confirm. */
  reviewFields: { key: string; label: string }[];
  /** A successful WRITE/RISKY step must have a read-back verifier. */
  requiresVerifier: boolean;
}

export const TOOL_SPECS: ToolSpec[] = [
  { name: "get_user_insights", kind: "READ",
    catalogLine: '- get_user_insights {focus?: "richest"|"newest"|"most_active"|"inactive"|"tier_split"|"all"} - ranked member analytics.',
    essentialArgs: [], reviewFields: [{ key: "focus", label: "Focus" }], requiresVerifier: false },
  { name: "get_member_count", kind: "READ",
    catalogLine: "- get_member_count {} - the total number of members only; prefer this over get_user_insights for count questions.",
    essentialArgs: [], reviewFields: [], requiresVerifier: false },
  { name: "get_flagged_messages", kind: "READ",
    catalogLine: "- get_flagged_messages {} - open moderation queue.",
    essentialArgs: [], reviewFields: [], requiresVerifier: false },
  { name: "get_safety_signals", kind: "READ",
    catalogLine: "- get_safety_signals {} - app-detected safety events.",
    essentialArgs: [], reviewFields: [], requiresVerifier: false },
  { name: "get_pricing", kind: "READ",
    catalogLine: "- get_pricing {} - coin packages, multipliers, cosmetics.",
    essentialArgs: [], reviewFields: [], requiresVerifier: false },
  { name: "get_audit_log", kind: "READ",
    catalogLine: "- get_audit_log {} - recent admin audit log.",
    essentialArgs: [], reviewFields: [], requiresVerifier: false },
  { name: "set_coin_packages", kind: "RISKY",
    catalogLine: "- set_coin_packages {packages: [{id, title, coins, bonus_coins, price_kes, price_usd, tag?}]} - REPLACE the price list (psychological pricing rules apply).",
    essentialArgs: ["packages"], reviewFields: [{ key: "packages", label: "New price list" }], requiresVerifier: true },
  { name: "set_cosmetic_price", kind: "WRITE",
    catalogLine: "- set_cosmetic_price {item_id, price_coins?, is_active?} - change one cosmetic.",
    essentialArgs: ["item_id"], reviewFields: [{ key: "item_id", label: "Item" }, { key: "price_coins", label: "Price (coins)" }, { key: "is_active", label: "Visible" }], requiresVerifier: true },
  { name: "adjust_coins", kind: "RISKY",
    catalogLine: "- adjust_coins {targets: [user_id,...], delta: number (up to 3 decimal places), reason} - grant (positive) or deduct (negative) coins; max 10000 per step, max 5 targets.",
    essentialArgs: ["targets", "delta"], reviewFields: [{ key: "delta", label: "Amount per member" }, { key: "target_names", label: "Members" }, { key: "reason", label: "Reason" }], requiresVerifier: true },
  { name: "gift_all_coins", kind: "RISKY",
    catalogLine: "- gift_all_coins {delta: number (up to 3 decimal places), reason} - gift coins to EVERY member at once; always use this for an all-users gift, never adjust_coins; no targets needed.",
    essentialArgs: ["delta"], reviewFields: [{ key: "delta", label: "Gift per member" }, { key: "reason", label: "Reason" }], requiresVerifier: true },
  { name: "deduct_all_coins", kind: "RISKY",
    catalogLine: "- deduct_all_coins {delta: number (up to 3 decimal places), reason} - deduct coins from EVERY member at once; always use this for an all-users deduction, never adjust_coins; no targets needed. All-or-nothing: aborts if any member cannot cover the full amount.",
    essentialArgs: ["delta"], reviewFields: [{ key: "delta", label: "Deduction per member" }, { key: "reason", label: "Reason" }], requiresVerifier: true },
  { name: "set_user_status", kind: "RISKY",
    catalogLine: '- set_user_status {targets: [user_id,...], status: "ACTIVE"|"SUSPENDED"|"BANNED", reason?, duration_hours?} - max 5 targets.',
    essentialArgs: ["targets", "status"], reviewFields: [{ key: "status", label: "New status" }, { key: "target_names", label: "Members" }, { key: "duration_hours", label: "Duration (hours)" }], requiresVerifier: true },
  { name: "set_user_tier", kind: "RISKY",
    catalogLine: '- set_user_tier {targets: [user_id,...], tier: "FREE"|"SUPPORTER"|"PREMIUM"} - max 5 targets.',
    essentialArgs: ["targets", "tier"], reviewFields: [{ key: "tier", label: "New tier" }, { key: "target_names", label: "Members" }], requiresVerifier: true },
  { name: "gift_artifact", kind: "WRITE",
    catalogLine: '- gift_artifact {targets: [user_id,...], artifact_name: "Luminescent Moon"|"Golden Blossom"|"Rose Obelisk"|"Dual Eclipse"|"Celestial Tiara", artifact_rarity?}',
    essentialArgs: ["targets", "artifact_name"], reviewFields: [{ key: "artifact_name", label: "Artifact" }, { key: "target_names", label: "Members" }], requiresVerifier: true },
  { name: "send_notice", kind: "WRITE",
    catalogLine: '- send_notice {targets: [user_id,...] or audience: "ALL"|"FREE"|"SUPPORTER"|"PREMIUM", title, body} - official CycleCare team notice; max 25 individual targets.',
    essentialArgs: ["title", "body"], reviewFields: [{ key: "title", label: "Title" }, { key: "body", label: "Message" }, { key: "audience", label: "Audience" }, { key: "target_names", label: "Audience" }], requiresVerifier: true },
  { name: "set_guides_enabled", kind: "RISKY",
    catalogLine: "- set_guides_enabled {enabled: boolean} - Community Guides kill switch.",
    essentialArgs: ["enabled"], reviewFields: [{ key: "enabled", label: "Guides enabled" }], requiresVerifier: true },
  { name: "update_guide", kind: "WRITE",
    catalogLine: "- update_guide {persona_id, daily_post_cap?, is_active?}",
    essentialArgs: ["persona_id"], reviewFields: [{ key: "persona_id", label: "Guide" }, { key: "daily_post_cap", label: "Daily post cap" }, { key: "is_active", label: "Active" }], requiresVerifier: true },
  { name: "moderate_message", kind: "RISKY",
    catalogLine: '- moderate_message {targets: [message_id,...], action: "DISMISS"|"REMOVE"|"RESTORE"} - max 10 targets.',
    essentialArgs: ["targets", "action"], reviewFields: [{ key: "action", label: "Action" }, { key: "targets", label: "Messages" }], requiresVerifier: true },
];

const CATALOG_HEADER = "Available tools (use only these, with only these args):";

export function buildToolCatalog(specs: ToolSpec[] = TOOL_SPECS): string {
  return `${CATALOG_HEADER}\n${specs.map((s) => s.catalogLine).join("\n")}`;
}

export function specFor(name: string, specs: ToolSpec[] = TOOL_SPECS): ToolSpec | undefined {
  return specs.find((s) => s.name === name);
}
