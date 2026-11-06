//! Effect-free full-field local Track planner. Not wired to Android/JNI.
//! Storage dates support canonical full Java LocalDate range; no chrono narrowing.
//! V2 explicitly carries the settings read dependency even if no anchor is
//! written. A consumer MUST check every read and mutation base in the SAME
//! transaction as domain/revision/journal/receipt writes, under current full
//! identity exclusion. The older LabCommitPlan cannot represent these reads;
//! silently adapting to that V1 contract is forbidden.
use crate::{local_date::LocalDate, kotlin_text::kotlin_is_blank, sync_codec as codec};
use codec::{CycleDayLog, CycleSettings, JsonValue};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Identity {
    pub namespace: String, pub effective: String, pub instance: String,
    pub epoch: i64, pub session: i64, pub generation: i64, pub local: bool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlanError { Identity, Stale, Operation, Date, Revision, NonFinite, Empty, Text, Overflow, Conflict }
fn text(s: &str, limit: usize) -> bool { s.encode_utf16().count() <= limit }
impl Identity {
    pub fn validate(&self) -> Result<(), PlanError> {
        if [&self.namespace, &self.effective, &self.instance].iter().any(|s| kotlin_is_blank(s) || !text(s,128)) || [self.epoch,self.session,self.generation].iter().any(|n| *n<0) { Err(PlanError::Identity) } else { Ok(()) }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReadDependency { pub record: String, pub revision: i64, pub payload_hash: String }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Mutation { pub record: String, pub expected_revision: i64, pub payload: Option<String>, pub payload_hash: String, pub bbt_bits: Option<u32>, pub sleep_bits: Option<u32> }
/// Immutable via getters, created only by validated planners. Persistence and
/// identity activation are separate host obligations, not methods on this type.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitPlan { identity: Identity, operation: String, reads: Vec<ReadDependency>, mutations: Vec<Mutation>, digest: String }
impl CommitPlan {
    pub fn identity(&self)->&Identity {&self.identity}
    pub fn operation(&self)->&str {&self.operation}
    pub fn reads(&self)->&[ReadDependency] {&self.reads}
    pub fn mutations(&self)->&[Mutation] {&self.mutations}
    pub fn intent_digest(&self)->&str {&self.digest}
    /// Ordered AOSP canonical intent, versioned and bound to the entire captured
    /// identity, read preconditions, mutation bases, payload hashes and raw bits.
    pub fn canonical_intent(&self)->String {
        let s=|v:&str|JsonValue::Str(v.into());
        let id=&self.identity;
        let identity=JsonValue::Array(vec![s(&id.namespace),s(&id.effective),s(&id.instance),JsonValue::Long(id.epoch),JsonValue::Long(id.session),JsonValue::Long(id.generation),JsonValue::Bool(id.local)]);
        let reads=JsonValue::Array(self.reads.iter().map(|r|JsonValue::Array(vec![s(&r.record),JsonValue::Long(r.revision),s(&r.payload_hash)])).collect());
        let mutations=JsonValue::Array(self.mutations.iter().map(|m|JsonValue::Array(vec![s(&m.record),JsonValue::Long(m.expected_revision),s(if m.payload.is_some(){"UPSERT"}else{"DELETE"}),s(&m.payload_hash),m.bbt_bits.map(|v|JsonValue::Long(v as i64)).unwrap_or(JsonValue::Null),m.sleep_bits.map(|v|JsonValue::Long(v as i64)).unwrap_or(JsonValue::Null)])).collect());
        codec::json_value_to_string(&JsonValue::Array(vec![JsonValue::Int(2),identity,s(&self.operation),reads,mutations]))
    }
}
fn start(captured:&Identity,current:&Identity,op:&str,revision:i64,date:&str)->Result<(),PlanError>{
    captured.validate()?;current.validate()?;
    if captured!=current{return Err(PlanError::Stale)}
    if kotlin_is_blank(op)||!text(op,128){return Err(PlanError::Operation)}
    if revision<0{return Err(PlanError::Revision)}
    if revision==i64::MAX{return Err(PlanError::Overflow)}
    if LocalDate::parse(date).is_none(){return Err(PlanError::Date)}
    Ok(())
}
fn finish(identity:&Identity,op:&str,reads:Vec<ReadDependency>,mutations:Vec<Mutation>)->CommitPlan{
    let mut p=CommitPlan{identity:identity.clone(),operation:op.into(),reads,mutations,digest:String::new()};p.digest=codec::sha256_hex(&p.canonical_intent());p
}
/// Full saved-record boundary, not TrackScreen edit-state behavior. Finite-only
/// and atomic log+anchor are intentional corrections to current Kotlin effects.
pub fn save_track(captured:&Identity,current:&Identity,op:&str,log:&CycleDayLog,log_revision:i64,settings:&CycleSettings,settings_revision:i64)->Result<CommitPlan,PlanError>{
    start(captured,current,op,log_revision,&log.date)?;
    if settings_revision<0{return Err(PlanError::Revision)}
    if !log.bbt_celsius.is_finite()||!log.sleep_hours.is_finite(){return Err(PlanError::NonFinite)}
    if [&log.flow,&log.energy,&log.mood,&log.symptoms,&log.vaginal_health,&log.urinary_health,&log.cervical_mucus,&log.ovulation_test,&log.sleep_quality,&log.notes,&settings.selected_goals_csv,&settings.last_period_start_date,&settings.pregnancy_due_date].iter().any(|s|!text(s,4096)){return Err(PlanError::Text)}
    if !crate::track_log::CycleDayLog::from(log).has_recorded_observations(){return Err(PlanError::Empty)}
    let payload=codec::log_payload(log);let before=codec::settings_payload(settings);
    let reads=vec![ReadDependency{record:"settings".into(),revision:settings_revision,payload_hash:codec::hash(Some(&before))}];
    let mut mutations=vec![Mutation{record:log.date.clone(),expected_revision:log_revision,payload_hash:codec::hash(Some(&payload)),payload:Some(payload),bbt_bits:Some(log.bbt_celsius.to_bits()),sleep_bits:Some(log.sleep_hours.to_bits())}];
    let date=LocalDate::parse(&log.date).ok_or(PlanError::Date)?;
    let prior=LocalDate::parse_java(&settings.last_period_start_date);
    // Difference avoids unchecked date addition at the calendar boundary.
    let anchor=["LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY"].contains(&log.flow.as_str()) && match prior{None=>true,Some(p)=>date.anchor_moves_after(p).map_err(|_|PlanError::Overflow)?};
    if anchor {
        if settings_revision==i64::MAX{return Err(PlanError::Overflow)}
        let mut after=settings.clone();after.last_period_start_date=log.date.clone();let payload=codec::settings_payload(&after);
        mutations.push(Mutation{record:"settings".into(),expected_revision:settings_revision,payload_hash:codec::hash(Some(&payload)),payload:Some(payload),bbt_bits:None,sleep_bits:None});
    }
    Ok(finish(captured,op,reads,mutations))
}
pub fn delete_track(captured:&Identity,current:&Identity,op:&str,date:&str,revision:i64)->Result<CommitPlan,PlanError>{
    start(captured,current,op,revision,date)?;
    Ok(finish(captured,op,vec![],vec![Mutation{record:date.into(),expected_revision:revision,payload:None,payload_hash:codec::hash(None),bbt_bits:None,sleep_bits:None}]))
}

/// Read-only view supplied INSIDE the consumer's write transaction/exclusion.
/// A missing record is revision zero with hash(None), not a guessed default.
pub trait TransactionView {
    fn current_identity(&self) -> &Identity;
    fn revision(&self, record: &str) -> i64;
    fn payload_hash(&self, record: &str) -> String;
}
impl CommitPlan {
    /// All reads, including unchanged settings, and mutation bases must match.
    /// This function cannot enforce the caller's transaction or lock lifetime.
    /// Durable receipt lookup must precede this for same-op replay, and compare
    /// complete V2 digest; never treat a stale base as a reason to apply twice.
    pub fn validate_transaction_view(&self, view: &impl TransactionView) -> Result<(),PlanError> {
        if view.current_identity()!=&self.identity {return Err(PlanError::Stale)}
        for r in &self.reads {
            if view.revision(&r.record)!=r.revision || view.payload_hash(&r.record)!=r.payload_hash {return Err(PlanError::Conflict)}
        }
        for m in &self.mutations {
            if view.revision(&m.record)!=m.expected_revision {return Err(PlanError::Conflict)}
        }
        Ok(())
    }
}
