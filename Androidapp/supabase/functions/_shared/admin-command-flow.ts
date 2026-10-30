// Testable Admin Command flow: coin dialog, step execution and message persistence.
// Imported by admin-command/index.ts and exercised directly by mocked Deno tests.
import { parseCoinRequest, applyCoinReply, isConfirmation, selectCandidate, startsCoinCommand } from "./coin-dialog.js";

export async function coinDialog(admin:any,userClient:any,adminId:string,text:string):Promise<any|null> {
 const fresh=parseCoinRequest(text);
 const {data:previous,error:stateError}=await admin.from("admin_coin_dialog_state").select("state,updated_at").eq("admin_user_id",adminId).maybeSingle();
 if(stateError) return {success:false,type:"chat",message:"I couldn't check the pending request. No change was made."};
 const retire=async(runId:string|undefined)=>{if(!runId)return;const {error}=await admin.from("admin_command_runs").update({status:"rejected"}).eq("id",runId).eq("admin_user_id",adminId).eq("status","proposed");if(error)throw new Error("Previous plan could not be retired safely");};
 const active=previous && Date.now()-Date.parse(previous.updated_at)<30*60_000 ? previous.state : null;
 if(previous&&!active){await retire(previous.state?.run_id);await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);}

 if(startsCoinCommand(text)&&!fresh){
   await retire(previous?.state?.run_id);
   await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);
   return {success:true,type:"clarify",message:"I couldn't read that new coin request safely, so I cleared the earlier pending request. Please give the amount and recipient again, for example: Debit 651 coins from name@example.com. No change was made."};
 }
 if(fresh) await retire(previous?.state?.run_id);
 if(!fresh&&active?.cancelled&&(isConfirmation(text)||/reason|decide|choose/i.test(text)))return {success:true,type:"chat",message:"The previous coin request was cancelled. Start a new request with an amount and recipient; no change has been made."};
 if(!fresh&&!active)return null;
 if(/^(cancel|stop|forget it)$/i.test(text.trim())) {await retire(active?.run_id);await admin.from("admin_coin_dialog_state").upsert({admin_user_id:adminId,state:{cancelled:true},updated_at:new Date().toISOString()});return {success:true,type:"chat",message:"Cancelled. No change was made."};}
 if(!fresh&&active?.awaiting==="reason"&&/\b(?:coins?|from|to)\b/i.test(text)&&/\d/.test(text)) {
   await retire(active?.run_id);
   await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);
   return {success:true,type:"clarify",message:"That looks like a changed amount or recipient, not a reason. I cleared the earlier request. Please state the new coin action in full; no change was made."};
 }
 if(active?.cancelled&&!fresh)return null;
 if(fresh?.scope==="all"||(!fresh&&active?.scope==="all"&&!active?.cancelled)){
   if(fresh&&fresh.delta<0){
     await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);
     return {success:true,type:"chat",message:"Taking coins from every member at once isn't something I'll do; a deduction has to name each member. To gift every member instead, say the amount, for example: Gift all users 2 coins each. No change was made."};
   }
   let gst=fresh?{scope:"all",delta:fresh.delta,reason:fresh.reason??null,awaiting:"reason"}:applyCoinReply(active,text);
   if(!Number.isInteger(gst.delta)||gst.delta<1||gst.delta>10000){
     await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);
     return {success:true,type:"chat",message:"Choose a whole gift between 1 and 10,000 coins for each member. No change has been made."};
   }
   if(!gst.reason){
     const {count,error:countError}=await admin.from("profiles").select("id",{count:"exact",head:true});
     if(countError||count===null||count<1)return {success:true,type:"chat",message:"I couldn't count the members just now. No change was made."};
     gst.awaiting="reason";
     const {error:gsaveError}=await admin.from("admin_coin_dialog_state").upsert({admin_user_id:adminId,state:gst,updated_at:new Date().toISOString()});
     if(gsaveError)throw new Error("Pending request could not be saved");
     const msg=`That's a gift of ${gst.delta} coins for each of the ${count} members, ${gst.delta*count} coins in total. What reason should I record? You can say "Admin adjustment", give your own reason, or ask me to choose.`;
     await persistMessage(admin,adminId,"assistant","chat",msg);
     return {success:true,type:"clarify",message:msg};
   }
   const {data:profs,error:profErr}=await admin.from("profiles").select("user_id,device_id,id");
   if(profErr||!Array.isArray(profs))return {success:true,type:"chat",message:"I couldn't list the members just now. No change was made."};
   const snapshot=profs.map((p:any)=>String(p.user_id||p.device_id||p.id));
   if(!snapshot.length||snapshot.length>5000)return {success:true,type:"chat",message:"I couldn't prepare the member list safely. No change was made."};
   const total=gst.delta*snapshot.length;
   const steps=[{tool:"gift_all_coins",label:`Gift ${gst.delta} coins to each of the ${snapshot.length} members (${total} coins in total). Reason: ${gst.reason}`,args:{delta:gst.delta,reason:gst.reason,run_key:crypto.randomUUID(),recipients:snapshot}}];
   const {data:proposed,error:proposeError}=await userClient.rpc("admin_command_propose",{p_prompt:steps[0].label,p_plan:{steps}});
   if(proposeError){const msg="I couldn't prepare the gift safely. Nothing was changed; please try again or say cancel.";await persistMessage(admin,adminId,"assistant","chat",msg);return {success:true,type:"clarify",message:msg};}
   await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",adminId);
   await persistMessage(admin,adminId,"assistant","plan",steps[0].label,proposed.run_id,{steps,requires_approval:true});
   return {success:true,type:"plan",run_id:proposed.run_id,requires_approval:true,summary:steps[0].label,plan:{steps}};
 }
 let st=fresh?{...fresh,recipient:null,awaiting:"recipient"}:applyCoinReply(active,text);
 const save=async()=>{const {error}=await admin.from("admin_coin_dialog_state").upsert({admin_user_id:adminId,state:st,updated_at:new Date().toISOString()});if(error)throw new Error("Pending request could not be saved");};
 const reply=async(message:string)=>{
   if(message===st.lastQuestion){
     st.repeatCount=(st.repeatCount??0)+1;
     message=st.awaiting==="reason" ? (st.repeatCount%2 ? 'Recipient and amount are already confirmed. Choose a reason: "Admin adjustment", your own short explanation, or say cancel.' : 'I still need only the reason, not the account. Say "you decide" to use Admin adjustment, or cancel.') : st.run_id ? (st.repeatCount%2 ? 'No new plan is needed. The existing approval card is ready; use its buttons or say cancel.' : 'The saved plan is unchanged. Review the card above before choosing Approve and run or Reject.') : 'I need an exact email to choose safely. Reply with one email from the candidates, or cancel.';
   }
   st.lastQuestion=message;
   await save();await persistMessage(admin,adminId,"assistant","chat",message);return {success:true,type:"clarify",message};};
 if(st.run_id&&!fresh)return reply("The plan is already prepared. Use Approve and run to make the change, Reject to discard it, or say cancel. No extra change has been made.");
 if(!Number.isInteger(st.delta)||st.delta===0||Math.abs(st.delta)>10000)return reply("Choose a non-zero whole number up to 10,000 coins. No change has been made.");
 if(!st.recipient && !fresh && active?.awaiting==="recipient") {
   const picked=selectCandidate(active.candidates,text);
   if(picked) {st.recipient=picked;st.query=picked.email;}
   else if(active.candidates?.length) {
     st.candidates=active.candidates;
     return reply("That choice isn't unique. Reply with a listed number, one exact email, or say cancel. No change was made.");
   }
 }
 if(!st.recipient){
   if(isConfirmation(text)&&st.candidates?.length!==1)return reply("Please choose a specific account by email from the list, or say cancel. No change has been made.");
   const {data,error}=await userClient.rpc("admin_find_recipients",{p_query:st.query});
   if(error||!Array.isArray(data))return reply("I couldn't check the member list. Try their exact email, or say cancel. No change has been made.");
   let candidates=[...new Map(data.map((p:any)=>[p.user_id,p])).values()] as any[];
   const q=st.query.toLowerCase();
   const strong=candidates.filter((p:any)=>String(p.display_name).toLowerCase().includes(q)||String(p.email).toLowerCase().includes(q));
   if(strong.length)candidates=strong;
   st.candidates=candidates;
   if(candidates.length!==1){st.awaiting="recipient";return reply(candidates.length?`Which member did you mean? No change has been made.\n${candidates.map((p:any,i:number)=>`${i+1}. ${p.display_name} (${p.email})`).join("\n")}\nReply with a number, their email, or say cancel.`:`No member matches "${st.query}". Try their full name, exact email, or say cancel. No change has been made.`);}
   st.recipient=candidates[0];
 }
 if(!st.reason){st.awaiting="reason";return reply(`The account is ${st.recipient.display_name} (${st.recipient.email}); the amount is ${Math.abs(st.delta)} coins to ${st.delta<0?"deduct":"grant"}. What reason should I record? You can say "Admin adjustment", give your own reason, or ask me to choose.`);}
 // Resolve account again through exact email before proposal. Never trust stored identifiers.
 const {data:bound,error}=await userClient.rpc("admin_resolve_recipient",{p_email:st.recipient.email});
 if(error||bound?.user_id!==st.recipient.user_id)return reply("The account changed or couldn't be confirmed. Please cancel and start again. No change was made.");
 const steps=[{tool:"adjust_coins",label:`${st.delta<0?"Deduct":"Grant"} ${Math.abs(st.delta)} coins ${st.delta<0?"from":"to"} ${bound.email}. Reason: ${st.reason}`,args:{targets:[bound.user_id],delta:st.delta,reason:st.reason}}];
 const prompt=`${st.delta<0?"Deduct":"Grant"} ${Math.abs(st.delta)} coins ${st.delta<0?"from":"to"} ${bound.email}. Reason: ${st.reason}`;
 const {data:proposed,error:proposeError}=await userClient.rpc("admin_command_propose",{p_prompt:prompt,p_plan:{steps}});
 if(proposeError)return reply("I couldn't prepare the change safely. Nothing was changed; please cancel and try again.");
 st.run_id=proposed.run_id;st.awaiting="approval";await save();
 await persistMessage(admin,adminId,"assistant","plan",steps[0].label,st.run_id,{steps,requires_approval:true});
 return {success:true,type:"plan",run_id:st.run_id,requires_approval:true,summary:steps[0].label,plan:{steps}};
}

export async function executeStep(userClient: any, tool: string, args: any, includeGuides = false): Promise<{ ok: boolean; result?: unknown; error?: string }> {
  const rpc = (name: string, params: Record<string, unknown>) => userClient.rpc(name, params);
  const targets: string[] = Array.isArray(args?.targets) ? args.targets.slice(0, 25).map(String) : [];
  try {
    switch (tool) {
      case "get_member_count": {
        const { data, error } = await rpc("admin_member_count", {});
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "get_user_insights": {
        const { data, error } = await rpc("admin_member_insights_scoped", {p_include_guides:includeGuides});
        if (error) return { ok: false, error: error.message };
        const focus = typeof args?.focus === "string" ? args.focus : "all";
        return { ok: true, result: focus === "all" ? data : { [focus]: data?.[focus] ?? null, guides_excluded: data?.guides_excluded !== false } };
      }
      case "get_flagged_messages": case "get_safety_signals": case "get_pricing": case "get_audit_log": {
        const { data, error } = await rpc("admin_get_control_data", {});
        if (error) return { ok: false, error: error.message };
        const section = tool === "get_flagged_messages" ? "flagged_messages" : tool === "get_safety_signals" ? "safety_events"
          : tool === "get_audit_log" ? "audit_log" : "app_config";
        return { ok: true, result: tool === "get_pricing" ? { ...(data?.app_config ?? {}), cosmetics: data?.cosmetics ?? [] } : data?.[section] ?? null };
      }
      case "set_coin_packages": {
        if (!Array.isArray(args?.packages) || args.packages.length < 1 || args.packages.length > 20) return { ok: false, error: "packages must be a list of 1-20 entries" };
        const { data, error } = await rpc("admin_update_app_config", { p_key: "coin_packages", p_value: args.packages });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "set_cosmetic_price": {
        const control = await rpc("admin_get_control_data", {});
        const item = (control.data?.cosmetics ?? []).find((c: any) => c.item_id === args?.item_id);
        if (!item) return { ok: false, error: `cosmetic ${args?.item_id} not found` };
        const { data, error } = await rpc("admin_upsert_cosmetic", {
          p_item_id: item.item_id, p_kind: item.kind, p_name: item.name, p_description: item.description,
          p_price_coins: typeof args?.price_coins === "number" ? Math.round(args.price_coins) : item.price_coins,
          p_rarity: item.rarity, p_is_active: typeof args?.is_active === "boolean" ? args.is_active : item.is_active,
          p_sort_order: item.sort_order ?? 0, p_premium_included: item.premium_included === true });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "adjust_coins": case "set_user_status": case "set_user_tier": case "gift_artifact": case "moderate_message": {
        const results: unknown[] = [];
        const list = tool === "moderate_message" ? targets : targets;
        for (const target of list) {
          let out;
          if (tool === "adjust_coins") out = await rpc("admin_apply_action", { p_action: "COINS", p_target_user_id: target, p_payload: { delta: Math.trunc(Number(args?.delta) || 0), reason: String(args?.reason ?? "Admin Command") } });
          else if (tool === "set_user_status") out = await rpc("admin_apply_action", { p_action: "STATUS", p_target_user_id: target, p_payload: { status: String(args?.status ?? ""), reason: args?.reason ?? null, duration_hours: args?.duration_hours ?? null } });
          else if (tool === "set_user_tier") out = await rpc("admin_apply_action", { p_action: "TIER", p_target_user_id: target, p_payload: { tier: String(args?.tier ?? "") } });
          else if (tool === "gift_artifact") out = await rpc("admin_apply_action", { p_action: "GIFT", p_target_user_id: target, p_payload: { artifact_name: String(args?.artifact_name ?? ""), artifact_rarity: args?.artifact_rarity ?? "Rare" } });
          else out = await rpc("admin_moderate_message", { p_message_id: target, p_action: String(args?.action ?? "") });
          if (out.error) results.push({ target, ok: false, error: out.error.message });
          else results.push({ target, ok: true, result: out.data });
        }
        const allOk = results.every((r: any) => r.ok);
        return { ok: allOk, result: results, error: allOk ? undefined : (results.find((r: any) => !r.ok)?.error ?? "step failed") };
      }
      case "gift_all_coins": {
        const { data, error } = await rpc("admin_gift_all_coins", {
          p_delta: Math.trunc(Number(args?.delta) || 0),
          p_reason: String(args?.reason ?? ""),
          p_run_key: String(args?.run_key ?? ""),
          p_recipients: Array.isArray(args?.recipients) && args.recipients.length ? args.recipients.map(String) : null });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "send_notice": {
        const audience = typeof args?.audience === "string" ? args.audience.toUpperCase() : (targets.length ? "CUSTOM_LIST" : "ALL");
        const { data, error } = await rpc("admin_dispatch_message", {
          p_target_audience: audience, p_message_type: "SYSTEM_ANNOUNCEMENT",
          p_title: String(args?.title ?? "CycleCare Notice"), p_body: String(args?.body ?? ""),
          p_action_url: null, p_custom_user_ids: targets, p_push_notification: true });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "set_guides_enabled": {
        const { data, error } = await rpc("admin_set_personas_enabled", { p_enabled: args?.enabled === true });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      case "update_guide": {
        const { data, error } = await rpc("admin_update_persona", {
          p_persona_id: String(args?.persona_id ?? ""),
          p_daily_post_cap: typeof args?.daily_post_cap === "number" ? Math.round(args.daily_post_cap) : null,
          p_activity_weight: null, p_is_active: typeof args?.is_active === "boolean" ? args.is_active : null });
        return error ? { ok: false, error: error.message } : { ok: true, result: data };
      }
      default:
        return { ok: false, error: `unknown tool ${tool}` };
    }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : "step failed" };
  }
}

export async function persistMessage(admin: any, adminId: string, role: string, kind: string, content: string, runId?: string | null, meta?: Record<string, unknown>): Promise<void> {
  try {
    await admin.from("admin_command_messages").insert({
      admin_user_id: adminId, role, kind, content, run_id: runId ?? null, meta: meta ?? {} });
  } catch { /* history must never break the reply path */ }
}
