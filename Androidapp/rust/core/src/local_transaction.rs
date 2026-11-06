//! Storage-neutral V5 local transaction protocol. No SQLite or platform API.
//! Current sole implementation is the private ScratchStore backend. This module
//! does not open a database, authorize a writer or qualify encrypted storage.
use crate::{local_commit_plan as p,sync_codec as c};
use std::collections::BTreeMap;
#[derive(Debug,PartialEq,Eq)]
pub enum Error { Plan(p::PlanError), Storage, Conflict, Stale, InvalidReuse, CorruptFloat, Overflow, InvalidSync }
impl From<p::PlanError> for Error {fn from(e:p::PlanError)->Self{Self::Plan(e)}}
#[derive(Debug,PartialEq,Eq)]
pub enum Outcome { Committed{replay:bool,receipt:String}, Unknown }
/// Cannot be fabricated externally or redirected to a different domain payload.
#[derive(Clone,Debug)]
pub struct Prepared { plan:p::CommitPlan, bases:BTreeMap<String,(i64,String)>, log:Option<c::CycleDayLog>, settings:c::CycleSettings, materialize:bool, digest:String, sync:Option<SyncIntent> }
impl Prepared {pub fn digest(&self)->&str{&self.digest} pub fn plan(&self)->&p::CommitPlan{&self.plan}}
/// Explicit production-shaped journal input, no permission to send network.
#[derive(Clone,Debug)]
pub struct SyncInput {pub owner:String,pub generation:i64,pub now_ms:i64,pub operation_ids:Vec<String>}
#[derive(Clone,Debug)]
struct SyncIntent {input:SyncInput,priors:BTreeMap<String,Option<(i64,i64)>>}
/// Same-connection transaction operations for the EXISTING V5 consumer.
/// Implementations must be private to one admitted owner and run every method
/// inside one already-acquired write transaction. No independent commits,
/// callbacks, authority mutation, raw connection or network may escape.
/// This is not permission or qualification to add an encrypted implementation.
pub(crate) trait CommitTransaction {
 fn transaction_active(&self)->bool;
 fn matches(&self,identity:&p::Identity)->Result<bool,Error>;
 fn receipt(&self,namespace:&str,operation:&str)->Result<Option<(String,String)>,Error>;
 fn sync_prior(&self,owner:&str,record:&str)->Result<Option<(i64,i64)>,Error>;
 fn revision(&self,record:&str)->Result<i64,Error>;
 fn physical(&self,record:&str)->Result<String,Error>;
 fn write_log(&self,log:Option<&c::CycleDayLog>,record:&str)->Result<(),Error>;
 fn write_settings(&self,settings:&c::CycleSettings)->Result<(),Error>;
 fn write_revision(&self,record:&str,revision:i64)->Result<(),Error>;
 fn write_journal(&self,namespace:&str,operation:&str,record:&str,revision:i64,payload:Option<&str>)->Result<(),Error>;
 fn write_sync(&self,input:&SyncInput,record:&str,index:usize,revision:i64,server:i64,payload:Option<&str>,hash:&str)->Result<(),Error>;
 fn log(&self,date:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>;
 fn write_receipt(&self,namespace:&str,operation:&str,digest:&str,receipt:&str)->Result<(),Error>;
}
/// Transaction body only: caller MUST roll back every error and commit before
/// publishing success. Prepared is still opaque, only original V5 preparation
/// may mint one. Current caller/implementation is ScratchStore only.
pub(crate) fn commit_transaction(backend:&dyn CommitTransaction,x:&Prepared,fault:usize)->Result<Outcome,Error>{
   if !backend.transaction_active(){return Err(Error::Storage)}
   // Current full identity precedes replay: an old owner must not gain an
   // active success projection. Matching-identity durable replay precedes OCC.
   if !backend.matches(x.plan.identity())?{return Err(Error::Stale)}
   let previous=backend.receipt(&x.plan.identity().namespace,x.plan.operation())?;
   if let Some((digest,receipt))=previous{if digest!=x.digest{return Err(Error::InvalidReuse)}if receipt!=expected_receipt(x)?{return Err(Error::Storage)}return Ok(Outcome::Committed{replay:true,receipt})}
   if let Some(sync)=&x.sync{for (record,prior) in &sync.priors{if backend.sync_prior(&sync.input.owner,record)?!=*prior{return Err(Error::Conflict)}}}
   for (record,(revision,hash)) in &x.bases{if backend.revision(record)?!=*revision||backend.physical(record)?!=*hash{return Err(Error::Conflict)}}
   // Exact physical fingerprint checks include absence. For absent settings,
   // materialize explicit captured defaults in this same write transaction.
   let mut step=0;let mut fail=||->Result<(),Error>{step+=1;if step==fault{Err(Error::Storage)}else{Ok(())}};
   let mut written=Vec::new();
   backend.write_log(x.log.as_ref(),&x.plan.mutations()[0].record)?;fail()?;
   written.push((x.plan.mutations()[0].record.clone(),x.plan.mutations()[0].payload.clone()));
   let anchor=x.plan.mutations().iter().any(|m|m.record=="settings");
   if anchor||x.materialize{let mut settings=x.settings.clone();if anchor{settings.last_period_start_date=x.log.as_ref().ok_or(Error::Storage)?.date.clone();}backend.write_settings(&settings)?;fail()?;written.push(("settings".into(),Some(c::settings_payload(&settings))));}
   let mut records=Vec::new();for (write_index,(record,payload)) in written.into_iter().enumerate(){let next=backend.revision(&record)?.checked_add(1).ok_or(Error::Overflow)?;backend.write_revision(&record,next)?;fail()?;backend.write_journal(&x.plan.identity().namespace,x.plan.operation(),&record,next,payload.as_deref())?;fail()?;if let Some(sync)=&x.sync{
     let prior=sync.priors.get(&record).ok_or(Error::InvalidSync)?.unwrap_or((0,0));let revision=prior.0.checked_add(1).ok_or(Error::Overflow)?;let hash=c::hash(payload.as_deref());
     // Preserve last acknowledged server revision. Local write does not imply
     // server ACK, consent grant, upload, retry-state reset or old-op deletion.
     backend.write_sync(&sync.input,&record,write_index,revision,prior.1,payload.as_deref(),&hash)?;
    }
    records.push(serde_json::json!([record,next,c::hash(payload.as_deref())]));}
   // Validate domain readback before durable receipt. No successful receipt
   // may cover silent SQLite numeric/string normalization of intended payload.
   if let Some(log)=&x.log {let read=backend.log(&log.date)?.ok_or(Error::Storage)?;if c::log_payload(&c::CycleDayLog::from(&read))!=c::log_payload(log){return Err(Error::Storage)}}
   let receipt=serde_json::json!([5,"Committed",records]).to_string();if receipt!=expected_receipt(x)?{return Err(Error::Storage)}backend.write_receipt(&x.plan.identity().namespace,x.plan.operation(),&x.digest,&receipt)?;fail()?;
   Ok(Outcome::Committed{replay:false,receipt})
}
/// Read dependencies captured under one admitted same-connection transaction.
/// Private until a reviewed encrypted implementation can prove this contract.
pub(crate) trait PrepareTransaction {
 fn transaction_active(&self)->bool;
 fn matches(&self,i:&p::Identity)->Result<bool,Error>;
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>;
 fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>;
 fn revision(&self,record:&str)->Result<i64,Error>;
 fn physical(&self,record:&str)->Result<String,Error>;
 fn sync_prior(&self,owner:&str,record:&str)->Result<Option<(i64,i64)>,Error>;
}
pub(crate) fn prepare_transaction(backend:&dyn PrepareTransaction,i:&p::Identity,op:&str,date:&str,log:Option<&c::CycleDayLog>,sync_input:Option<SyncInput>)->Result<Prepared,Error>{
   if !backend.transaction_active(){return Err(Error::Storage)}
   if !backend.matches(i)?{return Err(Error::Stale)}
   let stored=backend.settings()?;let profile=if stored.is_none(){backend.profile()?}else{None};
   let settings=stored.clone().unwrap_or_else(||{let mut s=c::CycleSettings::default();if let Some(p)=&profile{s.is_onboarding_completed=p.is_completed;s.selected_goals_csv=p.selected_goals_csv.clone();s.last_period_start_date=p.last_period_date_string.clone();s.average_cycle_length=p.cycle_length_days;s.period_duration=p.period_duration_days;s.remind_fertile=p.remind_fertile_window;}s});let sr=backend.revision("settings")?;let lr=backend.revision(date)?;
   let plan=match log{Some(l)=>p::save_track(i,i,op,l,lr,&settings,sr)?,None=>p::delete_track(i,i,op,date,lr)?};
   let mut bases=BTreeMap::new();bases.insert(date.into(),(lr,backend.physical(date)?));if log.is_some(){bases.insert("settings".into(),(sr,backend.physical("settings")?));}
   let materialize=log.is_some()&&stored.is_none();if materialize{bases.insert("profile".into(),(backend.revision("profile")?,backend.physical("profile")?));}
   let sync=if let Some(input)=sync_input{
    if crate::kotlin_text::kotlin_is_blank(&input.owner)||input.owner.encode_utf16().count()>128||input.generation!=i.generation||input.now_ms<0{return Err(Error::InvalidSync)}
    let mut records=vec![date.to_string()];if materialize||plan.mutations().iter().any(|m|m.record=="settings"){records.push("settings".into())}
    if input.operation_ids.len()!=records.len()||input.operation_ids.iter().any(|v|crate::kotlin_text::kotlin_is_blank(v)||v.encode_utf16().count()>128)||input.operation_ids.iter().collect::<std::collections::BTreeSet<_>>().len()!=records.len(){return Err(Error::InvalidSync)}
    let mut priors=BTreeMap::new();for record in records{let prior=backend.sync_prior(&input.owner,&record)?;if prior.map_or(false,|(r,s)|r<0||s<0){return Err(Error::InvalidSync)};priors.insert(record,prior);}
    Some(SyncIntent{input,priors})
   }else{None};
   let wire=sync.as_ref().map(|s|serde_json::json!([s.input.owner,s.input.generation,s.input.now_ms,s.input.operation_ids,s.priors]));
   let canonical=serde_json::json!([5,plan.intent_digest(),bases,materialize,wire]).to_string();let digest=c::sha256_hex(&canonical);
   Ok(Prepared{plan,bases,log:log.cloned(),settings,materialize,digest,sync})
}


/// Exact canonical V5 receipt derived only from immutable prepared intent/bases.
/// Comparing the entire bounded value rejects malformed/extra/duplicate fields,
/// wrong record/revision/hash and alternative JSON without re-reading new bases.
pub(crate) fn expected_receipt(x:&Prepared)->Result<String,Error>{
 let mut records=Vec::new();let first=x.plan.mutations().first().ok_or(Error::Storage)?;let revision=x.bases.get(&first.record).ok_or(Error::Storage)?.0.checked_add(1).ok_or(Error::Overflow)?;records.push(serde_json::json!([first.record,revision,c::hash(first.payload.as_deref())]));
 let anchor=x.plan.mutations().iter().any(|m|m.record=="settings");if anchor||x.materialize{let mut settings=x.settings.clone();if anchor{settings.last_period_start_date=x.log.as_ref().ok_or(Error::Storage)?.date.clone();}let revision=x.bases.get("settings").ok_or(Error::Storage)?.0.checked_add(1).ok_or(Error::Overflow)?;records.push(serde_json::json!(["settings",revision,c::hash(Some(&c::settings_payload(&settings)))]));}
 Ok(serde_json::json!([5,"Committed",records]).to_string())
}
