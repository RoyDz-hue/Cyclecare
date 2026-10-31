// Golden snapshot of the planner catalog wording in production use.
export const GOLDEN_CATALOG = `
Available tools (use only these, with only these args):
- get_user_insights {focus?: "richest"|"newest"|"most_active"|"inactive"|"tier_split"|"all"} - ranked member analytics.
- get_member_count {} - the total number of members only; prefer this over get_user_insights for count questions.
- get_flagged_messages {} - open moderation queue.
- get_safety_signals {} - app-detected safety events.
- get_pricing {} - coin packages, multipliers, cosmetics.
- get_audit_log {} - recent admin audit log.
- set_coin_packages {packages: [{id, title, coins, bonus_coins, price_kes, price_usd, tag?}]} - REPLACE the price list (psychological pricing rules apply).
- set_cosmetic_price {item_id, price_coins?, is_active?} - change one cosmetic.
- adjust_coins {targets: [user_id,...], delta: number (up to 3 decimal places), reason} - grant (positive) or deduct (negative) coins; max 10000 per step, max 5 targets.
- gift_all_coins {delta: number (up to 3 decimal places), reason} - gift coins to EVERY member at once; always use this for an all-users gift, never adjust_coins; no targets needed.
- deduct_all_coins {delta: number (up to 3 decimal places), reason} - deduct coins from EVERY member at once; always use this for an all-users deduction, never adjust_coins; no targets needed. All-or-nothing: aborts if any member cannot cover the full amount.
- set_user_status {targets: [user_id,...], status: "ACTIVE"|"SUSPENDED"|"BANNED", reason?, duration_hours?} - max 5 targets.
- set_user_tier {targets: [user_id,...], tier: "FREE"|"SUPPORTER"|"PREMIUM"} - max 5 targets.
- gift_artifact {targets: [user_id,...], artifact_name: "Luminescent Moon"|"Golden Blossom"|"Rose Obelisk"|"Dual Eclipse"|"Celestial Tiara", artifact_rarity?}
- send_notice {targets: [user_id,...] or audience: "ALL"|"FREE"|"SUPPORTER"|"PREMIUM", title, body} - official CycleCare team notice; max 25 individual targets.
- set_guides_enabled {enabled: boolean} - Community Guides kill switch.
- update_guide {persona_id, daily_post_cap?, is_active?}
- moderate_message {targets: [message_id,...], action: "DISMISS"|"REMOVE"|"RESTORE"} - max 10 targets.`;
