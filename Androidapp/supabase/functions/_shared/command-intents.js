// Small deterministic intents answered directly, without a plan card or the model.
export const asksMemberCount = (text) =>
  /^(?:how many (?:users?|members?)(?:\s+do we have|\s+are there)?|what(?:'s| is) the (?:total )?(?:number|count) of (?:users?|members?)|(?:total |current )?(?:user|member) count)\s*[?]?$/i.test(String(text ?? '').trim());
