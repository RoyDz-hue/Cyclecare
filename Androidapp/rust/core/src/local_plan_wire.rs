//! Strict versioned pure transport to local_commit_plan. No DB/clock/identity
//! inference. Raw float32 bits and all local settings fields cross intact.
use crate::{local_commit_plan as p,sync_codec as c};
use serde_json::Value as V;
fn array(v:&V,n:usize)->Result<&Vec<V>,String>{let a=v.as_array().ok_or("array")?;if a.len()!=n{return Err("length".into())}Ok(a)}
fn s(v:&V)->Result<String,String>{v.as_str().map(str::to_owned).ok_or("string".into())}
fn n(v:&V)->Result<i64,String>{v.as_i64().ok_or("long".into())}
fn i(v:&V)->Result<i32,String>{i32::try_from(n(v)?).map_err(|_|"int".into())}
fn b(v:&V)->Result<bool,String>{v.as_bool().ok_or("bool".into())}
fn f(v:&V)->Result<f32,String>{Ok(f32::from_bits(u32::try_from(n(v)?).map_err(|_|"float-bits")?))}
pub fn plan(payload:&str)->Result<String,String>{
 if payload.len()>131072{return Err("size".into())}
 let v:V=serde_json::from_str(payload).map_err(|_|"json")?;let a=array(&v,8)?;
 if s(&a[0])?!="local-plan-wire-v1"{return Err("version".into())}
 let id=array(&a[1],7)?;let identity=p::Identity{namespace:s(&id[0])?,effective:s(&id[1])?,instance:s(&id[2])?,epoch:n(&id[3])?,session:n(&id[4])?,generation:n(&id[5])?,local:b(&id[6])?};
 let operation=s(&a[2])?;let mode=s(&a[3])?;let date=s(&a[4])?;let rev=n(&a[5])?;let settings_rev=n(&a[6])?;
 let plan=match mode.as_str(){
  "DELETE"=>{if !a[7].is_null(){return Err("delete-body".into())}p::delete_track(&identity,&identity,&operation,&date,rev)},
  "SAVE"=>{let data=array(&a[7],2)?;let l=array(&data[0],15)?;let t=array(&data[1],15)?;
   let log=c::CycleDayLog{date:s(&l[0])?,flow:s(&l[1])?,energy:s(&l[2])?,mood:s(&l[3])?,symptoms:s(&l[4])?,vaginal_health:s(&l[5])?,urinary_health:s(&l[6])?,cervical_mucus:s(&l[7])?,ovulation_test:s(&l[8])?,bbt_celsius:f(&l[9])?,sleep_hours:f(&l[10])?,sleep_quality:s(&l[11])?,water_glasses:i(&l[12])?,notes:s(&l[13])?,pain_intensity:i(&l[14])?};
   if log.date!=date{return Err("date-binding".into())}
   let settings=c::CycleSettings{id:i(&t[0])?,is_onboarding_completed:b(&t[1])?,selected_goals_csv:s(&t[2])?,last_period_start_date:s(&t[3])?,average_cycle_length:i(&t[4])?,period_duration:i(&t[5])?,luteal_phase_days:i(&t[6])?,is_app_lock_enabled:b(&t[7])?,lock_timeout_minutes:i(&t[8])?,is_discreet_notification_mode:b(&t[9])?,is_pregnancy_mode:b(&t[10])?,pregnancy_due_date:s(&t[11])?,remind_period:b(&t[12])?,remind_fertile:b(&t[13])?,remind_daily_log:b(&t[14])?};
   if settings.id!=1{return Err("settings-id".into())}p::save_track(&identity,&identity,&operation,&log,rev,&settings,settings_rev)
  },_=>return Err("mode".into())
 }.map_err(|e|format!("{e:?}"))?;
 Ok(serde_json::json!(["local-plan-result-v1",plan.canonical_intent(),plan.intent_digest(),plan.reads().iter().map(|r|serde_json::json!([r.record,r.revision,r.payload_hash])).collect::<Vec<_>>(),plan.mutations().iter().map(|m|serde_json::json!([m.record,m.expected_revision,m.payload,m.payload_hash,m.bbt_bits,m.sleep_bits])).collect::<Vec<_>>()]).to_string())
}
