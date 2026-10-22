// Deterministic presentation only: no model call, inferred records or metrics.
const number = (v) => typeof v === 'number' && Number.isFinite(v) ? v.toLocaleString('en-KE') : 'not available';
const words = (v) => String(v ?? '').replace(/_/g, ' ').toLowerCase();
const member = (r) => r?.display_name || r?.name || 'Unnamed member';
const date = (v) => v ? String(v).slice(0, 10) : 'not recorded';
const list = (rows, render, empty) => Array.isArray(rows) && rows.length ? rows.map(render).join('\n') : empty;

export function formatCommandStep(step) {
  if (step?.ok !== true) return `${step?.label || 'This step'} could not be completed. No success is confirmed for this step.`;
  const r = step.result;
  switch (step.tool) {
    case 'adjust_coins': {
      if (step.verified !== true) return 'The coin change was accepted but not confirmed. Do not repeat it yet; review the balance and transaction history first.';
      const balances = step.balances || [];
      return 'Coin change confirmed.' + (balances.length ? '\n' + balances.map(x => `• Updated balance: ${number(x.balance)} coins.`).join('\n') : ' The updated balance was checked.');
    }
    case 'get_user_insights': {
      if (!r || typeof r !== 'object') return 'Member insights are unavailable right now.';
      const sections = [];
      if (r.richest) {
        const rows = r.richest;
        if (!rows.length) sections.push('There are no coin balances to compare yet.');
        else {
          const top = rows[0].coin_balance;
          const leaders = rows.filter(x => x.coin_balance === top);
          sections.push(leaders.length > 1 ? `The highest balance returned is ${number(top)} coins, shared by ${leaders.map(member).join(', ')}. There may be other ties beyond this top ${rows.length} list.` : `${member(rows[0])} has the highest balance in this list: ${number(top)} coins.`);
          sections.push(list(rows, (x, i) => `${i + 1}. ${member(x)}: ${number(x.coin_balance)} coins${x.tier ? ` (${words(x.tier)})` : ''}`, ''));
        }
      }
      if (r.newest) sections.push('Newest members:\n' + list(r.newest, x => `• ${member(x)}: joined ${date(x.created_at)}${x.status ? `, ${words(x.status)}` : ''}`, 'No members found.'));
      if (r.most_active) sections.push('Most active members in the last 30 days:\n' + list(r.most_active, x => `• ${member(x)}: ${number(x.logs_30d)} logs; last entry ${date(x.last_log_date)}`, 'No activity recorded.'));
      if (r.inactive) sections.push('Members without a recent log:\n' + list(r.inactive, x => `• ${member(x)}: last log ${date(x.last_log_date)}`, 'No inactive members found.'));
      if (r.tier_split) sections.push('Membership breakdown:\n' + Object.entries(r.tier_split).map(([tier, n]) => `• ${words(tier)}: ${number(n)} members`).join('\n'));
      if (typeof r.restricted === 'number') sections.push(`${number(r.restricted)} members are suspended or banned.`);
      return sections.join('\n\n') || 'No member insights were returned.';
    }
    case 'get_pricing': {
      const packages = r?.coin_packages ?? r?.app_config?.coin_packages;
      const sections = ['Coin packages:\n' + list(packages, x => `• ${x.title || 'Coin pack'}: ${number(x.coins)} coins${x.bonus_coins ? ` + ${number(x.bonus_coins)} bonus` : ''}, KES ${number(x.price_kes)}${typeof x.price_usd === 'number' ? ` / US$${x.price_usd.toFixed(2)}` : ''}`, 'No coin packages found.')];
      if (Array.isArray(r?.cosmetics)) sections.push('Cosmetic prices:\n' + list(r.cosmetics, x => `• ${x.name || 'Unnamed item'}: ${number(x.price_coins)} coins${x.is_active === false ? ' (not available)' : ''}`, 'No cosmetic items found.'));
      return sections.join('\n\n');
    }
    case 'get_flagged_messages': return list(r, x => `• ${x.sender_alias || 'Unnamed member'}${x.topic_title ? ` in ${x.topic_title}` : ''}: "${x.content || '(no message text)'}"${x.removed_at ? ' (removed)' : ' (awaiting review)'}`, 'There are no flagged messages to review.');
    case 'get_safety_signals': return list(r, x => `• ${member(x)}: ${words(x.level) || 'safety'} signal, ${words(x.trigger_key) || 'reason not recorded'}; ${x.resolved_at ? 'resolved' : 'awaiting review'} (${date(x.created_at)}).`, 'No safety signals have been recorded.');
    case 'get_audit_log': return list(r, x => `• ${words(x.admin_action) || 'Admin action'} on ${date(x.created_at)}.`, 'No admin activity has been recorded.');
    default: {
      const labels = {adjust_coins:'Coin balance changes',set_user_status:'Account status changes',set_user_tier:'Membership changes',gift_artifact:'Artifact gifts',send_notice:'The team notice',set_coin_packages:'The coin packages',set_cosmetic_price:'The cosmetic price',set_guides_enabled:'The Community Guides setting',update_guide:'The Community Guide update',moderate_message:'The moderation changes'};
      let text = `${labels[step.tool] || 'The requested change'} completed successfully.`;
      if (Array.isArray(r)) text += ` ${r.filter(x => x?.ok === true).length} of ${r.length} targets completed.`;
      if (step.verified === true) text += ' Checked afterwards and confirmed.';
      else if (step.verified === false) text += ' The follow-up check did not confirm the expected state. Please review before trying again.';
      else text += ' The application accepted the change; a separate follow-up check was not available.';
      return text;
    }
  }
}
export function formatCommandOutcome(outcome) {
  const steps = Array.isArray(outcome?.steps) ? outcome.steps : [];
  if (!steps.length) return outcome?.status === 'done' ? 'The request completed. No further details were returned.' : 'This request has not completed. No completed steps are confirmed.';
  return steps.map(formatCommandStep).join('\n\n');
}
