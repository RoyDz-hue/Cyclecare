//! Scratch SQLite consumer on the seven-table Room DDL mirror. NOT an Android
//! opener/migration or encrypted-store implementation. No production file API.
//! Extra tables are experimental local protocol metadata, NOT Room v10.
use crate::{local_commit_plan as p, storage_db::StorageDb, sync_codec as c};
use rusqlite::{params,OptionalExtension,types::ValueRef};
pub use crate::local_transaction::{Error,Outcome,Prepared,SyncInput};
use crate::local_transaction::{PrepareTransaction,CommitTransaction,prepare_transaction,commit_transaction};
impl From<rusqlite::Error> for Error {fn from(_:rusqlite::Error)->Self{Self::Storage}}
pub struct ScratchStore {db:StorageDb}
const EXTRA:&str="CREATE TABLE local_identity(id INTEGER PRIMARY KEY CHECK(id=1),json TEXT NOT NULL); CREATE TABLE local_revisions(record TEXT PRIMARY KEY,revision INTEGER NOT NULL); CREATE TABLE local_receipts(namespace TEXT NOT NULL,operation TEXT NOT NULL,digest TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(namespace,operation)); CREATE TABLE local_journal(namespace TEXT NOT NULL,operation TEXT NOT NULL,record TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT,PRIMARY KEY(namespace,operation,record)); CREATE TABLE local_float_bits(date TEXT PRIMARY KEY,physical_hash TEXT NOT NULL,bbt_bits INTEGER NOT NULL,sleep_bits INTEGER NOT NULL); CREATE TRIGGER local_float_invalidate_update AFTER UPDATE ON cycle_day_logs BEGIN DELETE FROM local_float_bits WHERE date=OLD.dateString OR date=NEW.dateString; END; CREATE TRIGGER local_float_invalidate_delete AFTER DELETE ON cycle_day_logs BEGIN DELETE FROM local_float_bits WHERE date=OLD.dateString; END; CREATE TRIGGER local_float_invalidate_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM local_float_bits WHERE date=NEW.dateString; END;";
fn identity(i:&p::Identity)->String {serde_json::json!([i.namespace,i.effective,i.instance,i.epoch,i.session,i.generation,i.local]).to_string()}
impl ScratchStore {
 pub fn new_memory(i:&p::Identity)->Result<Self,Error>{i.validate()?;let db=StorageDb::open_memory()?;db.conn.execute_batch(EXTRA)?;db.conn.execute("INSERT INTO local_identity VALUES(1,?1)",[identity(i)])?;Ok(Self{db})}
 /// RAII rollback covers ordinary errors and unwinding before commit.
 /// A commit error still does not authorize retry with a new operation ID.
 fn transaction<T>(&self,action:impl FnOnce()->Result<T,Error>)->Result<T,Error>{let tx=rusqlite::Transaction::new_unchecked(&self.db.conn,rusqlite::TransactionBehavior::Immediate)?;let value=action()?;tx.commit()?;Ok(value)}
 fn matches(&self,i:&p::Identity)->Result<bool,Error>{Ok(self.db.conn.query_row("SELECT json FROM local_identity WHERE id=1",[],|r|r.get::<_,String>(0))?==identity(i))}
 fn revision(&self,record:&str)->Result<i64,Error>{Ok(self.db.conn.query_row("SELECT revision FROM local_revisions WHERE record=?1",[record],|r|r.get(0)).optional()?.unwrap_or(0))}
 /// Fingerprint ALL physical columns and SQLite value types (REAL uses raw64
 /// bits); catches local-only settings changes and same-revision domain drift.
 fn physical_row(&self,record:&str)->Result<String,Error>{
  use crate::platform_record_cells::{self,Cell};let sql=if record=="settings"{"SELECT * FROM cycle_settings WHERE id=1"}else if record=="profile"{"SELECT * FROM onboarding_profiles WHERE id=1"}else{"SELECT * FROM cycle_day_logs WHERE dateString=?1"};let mut stmt=self.db.conn.prepare(sql)?;let count=stmt.column_count();let mut rows=if record=="settings"||record=="profile"{stmt.query([])?}else{stmt.query([record])?};let mut cells=vec![];if let Some(row)=rows.next()?{for n in 0..count{cells.push(match row.get_ref(n)?{ValueRef::Null=>Cell::Null,ValueRef::Integer(v)=>Cell::Integer(v),ValueRef::Real(v)=>Cell::Real(v),ValueRef::Text(v)=>Cell::Text(std::str::from_utf8(v).map_err(|_|Error::Storage)?.into()),ValueRef::Blob(_)=>return Err(Error::Storage)})}}platform_record_cells::physical_row(&cells)
 }
 fn physical(&self,record:&str)->Result<String,Error>{
  let base=self.physical_row(record)?;
  let bits:Option<(String,i64,i64)>=if record=="settings"||record=="profile"{None}else{self.db.conn.query_row("SELECT physical_hash,bbt_bits,sleep_bits FROM local_float_bits WHERE date=?1",[record],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?};
  Ok(c::sha256_hex(&serde_json::json!([base,bits]).to_string()))
 }
 pub fn prepare_save(&self,i:&p::Identity,op:&str,log:&c::CycleDayLog)->Result<Prepared,Error>{self.prepare(i,op,&log.date,Some(log),None)}
 pub fn prepare_delete(&self,i:&p::Identity,op:&str,date:&str)->Result<Prepared,Error>{self.prepare(i,op,date,None,None)}
 fn prepare(&self,i:&p::Identity,op:&str,date:&str,log:Option<&c::CycleDayLog>,sync_input:Option<SyncInput>)->Result<Prepared,Error>{self.transaction(||prepare_transaction(self,i,op,date,log,sync_input))}
 pub(crate) fn prepare_save_sync(&self,i:&p::Identity,op:&str,log:&c::CycleDayLog,sync:SyncInput)->Result<Prepared,Error>{self.prepare(i,op,&log.date,Some(log),Some(sync))}
 pub(crate) fn prepare_delete_sync(&self,i:&p::Identity,op:&str,date:&str,sync:SyncInput)->Result<Prepared,Error>{self.prepare(i,op,date,None,Some(sync))}
 fn sync_prior(&self,owner:&str,record:&str)->Result<Option<(i64,i64)>,Error>{Ok(self.db.conn.query_row("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=?1 AND recordId=?2",params![owner,record],|r|Ok((r.get(0)?,r.get(1)?))).optional()?)}
 pub fn commit(&self,prepared:&Prepared)->Result<Outcome,Error>{self.commit_inner(prepared,0,false)}
 fn commit_inner(&self,x:&Prepared,fault:usize,unknown:bool)->Result<Outcome,Error>{
  let outcome=self.transaction(||commit_transaction(self,x,fault))?;if unknown&&!matches!(outcome,Outcome::Committed{replay:true,..}){Ok(Outcome::Unknown)}else{Ok(outcome)}
 }
 pub fn settings(&self)->Result<Option<c::CycleSettings>,Error>{Ok(self.db.get_settings()?)}
 pub fn log(&self,date:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{
  let mut row=self.db.get_log_by_date(date)?;
  let bits:Option<(String,i64,i64)>=self.db.conn.query_row("SELECT physical_hash,bbt_bits,sleep_bits FROM local_float_bits WHERE date=?1",[date],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
  if let Some((hash,b,s))=bits{
   if hash!=self.physical_row(date)?||!(0..=u32::MAX as i64).contains(&b)||!(0..=u32::MAX as i64).contains(&s){return Err(Error::CorruptFloat)}
   let l=row.as_mut().ok_or(Error::CorruptFloat)?;let b=f32::from_bits(b as u32);let s=f32::from_bits(s as u32);
   if !b.is_finite()||!s.is_finite()||b!=l.bbt_celsius||s!=l.sleep_hours{return Err(Error::CorruptFloat)}
   l.bbt_celsius=b;l.sleep_hours=s;
  }Ok(row)
 }
 /// Pre-activation gate over stored dates, never modifies or discards a row.
 /// Only scratch data can be read here. Real existing user-data assessment is
 /// still REQUIRED before activation; invalid/noncanonical data is preserved.
 pub fn unsupported_dates(&self)->Result<Vec<String>,Error>{let mut out=vec![];for l in self.db.get_all_logs()?{if crate::local_date::LocalDate::parse(&l.date_string).is_none(){out.push(l.date_string)}}Ok(out)}
 pub(crate) fn bound_identity(&self)->Result<p::Identity,Error>{let raw:String=self.db.conn.query_row("SELECT json FROM local_identity WHERE id=1",[],|r|r.get(0))?;let (namespace,effective,instance,epoch,session,generation,local):(String,String,String,i64,i64,i64,bool)=serde_json::from_str(&raw).map_err(|_|Error::Storage)?;let i=p::Identity{namespace,effective,instance,epoch,session,generation,local};i.validate()?;Ok(i)}
 pub(crate) fn transition_bound(&self,expected:&p::Identity,next:&p::Identity)->Result<(),Error>{next.validate()?;if next.namespace!=expected.namespace||next.instance!=expected.instance||next.epoch<=expected.epoch{return Err(Error::Stale)}self.transaction(||{if !self.matches(expected)?{return Err(Error::Stale)}self.db.conn.execute("UPDATE local_identity SET json=?1 WHERE id=1",[identity(next)])?;Ok(())})}
 #[cfg(feature="test-support")]
 pub fn prepare_save_sync_for_test(&self,i:&p::Identity,op:&str,log:&c::CycleDayLog,sync:SyncInput)->Result<Prepared,Error>{self.prepare_save_sync(i,op,log,sync)}
 #[cfg(feature="test-support")]
 pub fn prepare_delete_sync_for_test(&self,i:&p::Identity,op:&str,date:&str,sync:SyncInput)->Result<Prepared,Error>{self.prepare_delete_sync(i,op,date,sync)}
 #[cfg(feature="test-support")]
 pub fn inject_sql_for_test(&self,sql:&str)->Result<(),Error>{self.db.conn.execute_batch(sql)?;Ok(())}
 #[cfg(feature="test-support")]
 pub fn commit_fault_for_test(&self,x:&Prepared,step:usize,unknown:bool)->Result<Outcome,Error>{self.commit_inner(x,step,unknown)}
 #[cfg(feature="test-support")]
 pub fn transition_for_test(&self,next:&p::Identity)->Result<(),Error>{next.validate()?;self.transaction(||self.db.conn.execute("UPDATE local_identity SET json=?1 WHERE id=1",[identity(next)]).map(|_|()).map_err(Error::from))}
}

impl CommitTransaction for ScratchStore {
 fn transaction_active(&self)->bool{!self.db.conn.is_autocommit()}
 fn matches(&self,i:&p::Identity)->Result<bool,Error>{ScratchStore::matches(self,i)}
 fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>{Ok(self.db.conn.query_row("SELECT digest,result FROM local_receipts WHERE namespace=?1 AND operation=?2",params![n,o],|r|Ok((r.get(0)?,r.get(1)?))).optional()?)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{ScratchStore::sync_prior(self,o,r)}
 fn revision(&self,r:&str)->Result<i64,Error>{ScratchStore::revision(self,r)}
 fn physical(&self,r:&str)->Result<String,Error>{ScratchStore::physical(self,r)}
 fn write_log(&self,log:Option<&c::CycleDayLog>,record:&str)->Result<(),Error>{if let Some(log)=log{self.db.insert_or_update_log(&crate::track_log::CycleDayLog::from(log))?;let hash=self.physical_row(&log.date)?;self.db.conn.execute("INSERT OR REPLACE INTO local_float_bits VALUES(?1,?2,?3,?4)",params![log.date,hash,log.bbt_celsius.to_bits() as i64,log.sleep_hours.to_bits() as i64])?;}else{self.db.delete_log(record)?;}Ok(())}
 fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>{Ok(self.db.save_settings(s)?)}
 fn write_revision(&self,r:&str,v:i64)->Result<(),Error>{self.db.conn.execute("INSERT OR REPLACE INTO local_revisions VALUES(?1,?2)",params![r,v])?;Ok(())}
 fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,payload:Option<&str>)->Result<(),Error>{self.db.conn.execute("INSERT INTO local_journal VALUES(?1,?2,?3,?4,?5)",params![n,o,r,v,payload])?;Ok(())}
 fn write_sync(&self,i:&SyncInput,r:&str,index:usize,v:i64,server:i64,payload:Option<&str>,hash:&str)->Result<(),Error>{let child=i.operation_ids.get(index).ok_or(Error::InvalidSync)?;self.db.conn.execute("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?1,?2,?3,?4,?5,?6,?7)",params![i.owner,r,v,if payload.is_none(){None}else{Some(hash)},payload.is_none(),i.now_ms,server])?;self.db.conn.execute("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,0,0,NULL,NULL,NULL)",params![i.owner,child,r,if payload.is_none(){"DELETE"}else{"UPSERT"},server,v,payload,hash,i.generation])?;Ok(())}
 fn log(&self,d:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{ScratchStore::log(self,d)}
 fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>{self.db.conn.execute("INSERT INTO local_receipts VALUES(?1,?2,?3,?4)",params![n,o,d,r])?;Ok(())}
}

impl PrepareTransaction for ScratchStore {
 fn transaction_active(&self)->bool{!self.db.conn.is_autocommit()}
 fn matches(&self,i:&p::Identity)->Result<bool,Error>{ScratchStore::matches(self,i)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{Ok(self.db.get_settings()?)}
 fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>{Ok(self.db.get_profile()?)}
 fn revision(&self,r:&str)->Result<i64,Error>{ScratchStore::revision(self,r)}
 fn physical(&self,r:&str)->Result<String,Error>{ScratchStore::physical(self,r)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{ScratchStore::sync_prior(self,o,r)}
}
#[cfg(test)]mod transaction_boundary_tests {
 use super::*;
 #[test]fn direct_transaction_body_without_owned_transaction_rejects_before_effects(){let i=p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-instance".into(),epoch:1,session:1,generation:0,local:true};let s=ScratchStore::new_memory(&i).unwrap();let mut l=crate::track_log::CycleDayLog::new("2024-04-03");l.notes="synthetic".into();let l=c::CycleDayLog::from(&l);assert!(matches!(prepare_transaction(&s,&i,"dummy-op",&l.date,Some(&l),None),Err(Error::Storage)));let prepared=s.prepare_save(&i,"dummy-op",&l).unwrap();assert_eq!(commit_transaction(&s,&prepared,0),Err(Error::Storage));assert!(s.log(&l.date).unwrap().is_none());assert!(s.settings().unwrap().is_none());assert!(matches!(s.commit(&prepared).unwrap(),Outcome::Committed{replay:false,..}));}
}
#[cfg(test)]mod backend_order_tests {
 use super::*;use std::cell::RefCell;
 struct Boundary {active:bool,current:bool,previous:Option<(String,String)>,calls:RefCell<Vec<&'static str>>}
 impl CommitTransaction for Boundary {
  fn transaction_active(&self)->bool{self.calls.borrow_mut().push("transaction");self.active}
  fn matches(&self,_:&p::Identity)->Result<bool,Error>{self.calls.borrow_mut().push("identity");Ok(self.current)}
  fn receipt(&self,_:&str,_:&str)->Result<Option<(String,String)>,Error>{self.calls.borrow_mut().push("receipt");Ok(self.previous.clone())}
  fn sync_prior(&self,_:&str,_:&str)->Result<Option<(i64,i64)>,Error>{panic!("unexpected sync access")}
  fn revision(&self,_:&str)->Result<i64,Error>{panic!("unexpected OCC access")}
  fn physical(&self,_:&str)->Result<String,Error>{panic!("unexpected physical access")}
  fn write_log(&self,_:Option<&c::CycleDayLog>,_:&str)->Result<(),Error>{panic!("unexpected write")}
  fn write_settings(&self,_:&c::CycleSettings)->Result<(),Error>{panic!("unexpected write")}
  fn write_revision(&self,_:&str,_:i64)->Result<(),Error>{panic!("unexpected write")}
  fn write_journal(&self,_:&str,_:&str,_:&str,_:i64,_:Option<&str>)->Result<(),Error>{panic!("unexpected write")}
  fn write_sync(&self,_:&SyncInput,_:&str,_:usize,_:i64,_:i64,_:Option<&str>,_:&str)->Result<(),Error>{panic!("unexpected write")}
  fn log(&self,_:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{panic!("unexpected row read")}
  fn write_receipt(&self,_:&str,_:&str,_:&str,_:&str)->Result<(),Error>{panic!("unexpected receipt write")}
 }
 fn proposal()->Prepared{let i=p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-instance".into(),epoch:1,session:1,generation:0,local:true};let s=ScratchStore::new_memory(&i).unwrap();let mut l=crate::track_log::CycleDayLog::new("2024-04-03");l.notes="synthetic".into();s.prepare_save(&i,"dummy-op",&c::CycleDayLog::from(&l)).unwrap()}
 #[test]fn missing_transaction_and_stale_identity_never_read_receipt_or_write(){let p=proposal();for(active,current,expected)in[(false,true,vec!["transaction"]),(true,false,vec!["transaction","identity"]) ]{let b=Boundary{active,current,previous:Some((p.digest().to_owned(),"unusable-old-receipt".into())),calls:RefCell::new(vec![])};assert!(commit_transaction(&b,&p,0).is_err());assert_eq!(*b.calls.borrow(),expected);}}
 #[test]fn exact_receipt_replay_and_invalid_reuse_precede_all_occ_and_writes(){let p=proposal();for matching in [true,false]{let b=Boundary{active:true,current:true,previous:Some((if matching{p.digest().to_owned()}else{"different-intent".into()},crate::local_transaction::expected_receipt(&p).unwrap())),calls:RefCell::new(vec![])};let r=commit_transaction(&b,&p,0);if matching{assert_eq!(r,Ok(Outcome::Committed{replay:true,receipt:crate::local_transaction::expected_receipt(&p).unwrap()}));}else{assert_eq!(r,Err(Error::InvalidReuse));}assert_eq!(*b.calls.borrow(),vec!["transaction","identity","receipt"]);}}
}

#[cfg(test)]mod unwind_tests{
 use super::*;
 #[test]fn panic_after_real_domain_write_rolls_back_and_releases_transaction(){let i=p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-instance".into(),epoch:1,session:1,generation:0,local:true};let s=ScratchStore::new_memory(&i).unwrap();let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let _:Result<(),Error>=s.transaction(||{s.db.conn.execute("INSERT INTO local_revisions VALUES('synthetic-row',1)",[])?;panic!("synthetic precommit panic")});}));assert!(result.is_err());assert!(s.db.conn.is_autocommit());assert_eq!(s.revision("synthetic-row").unwrap(),0);s.transaction(||{s.db.conn.execute("INSERT INTO local_revisions VALUES('synthetic-row',1)",[])?;Ok(())}).unwrap();assert_eq!(s.revision("synthetic-row").unwrap(),1);}
}

#[cfg(test)]mod platform_owner_tests{
 use super::*;use crate::platform_record_store::{Connection,PlatformStore};use std::sync::{Arc,atomic::{AtomicBool,Ordering}};
 struct Host{store:ScratchStore,closed:Arc<AtomicBool>,commit_fails:bool,rollback_fails:bool,commits:std::cell::Cell<usize>}
 impl Connection for Host{
 fn begin(&self)->Result<(),Error>{self.store.db.conn.execute_batch("BEGIN IMMEDIATE")?;Ok(())}
 fn commit(&self)->Result<(),Error>{self.store.db.conn.execute_batch("COMMIT")?;self.commits.set(self.commits.get()+1);if self.commit_fails&&self.commits.get()==2{Err(Error::Storage)}else{Ok(())}}
 fn rollback(&self)->Result<(),Error>{if self.rollback_fails{return Err(Error::Storage)}if self.store.db.conn.is_autocommit(){return Ok(())}self.store.db.conn.execute_batch("ROLLBACK")?;Ok(())}
 fn preparation(&self)->&dyn PrepareTransaction{&self.store}
 fn mutation(&self)->&dyn CommitTransaction{&self.store}
 fn log(&self,d:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{self.store.log(d)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.store.settings()}
 fn close(&mut self){self.closed.store(true,Ordering::SeqCst);}
 }
 fn id()->p::Identity{p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-instance".into(),epoch:1,session:1,generation:0,local:true}}
 #[test]fn platform_owner_runs_same_v5_prepare_commit_and_replay_then_closes(){let i=id();let closed=Arc::new(AtomicBool::new(false));let host=Host{store:ScratchStore::new_memory(&i).unwrap(),closed:closed.clone(),commit_fails:false,rollback_fails:false,commits:std::cell::Cell::new(0)};let mut owner=PlatformStore::qualified(Box::new(host));let mut l=crate::track_log::CycleDayLog::new("2024-04-03");l.flow_level="LIGHT".into();let l=c::CycleDayLog::from(&l);let p=owner.prepare(&i,"platform-op",&l.date,Some(&l),None).unwrap();assert!(matches!(owner.commit(&p).unwrap(),Outcome::Committed{replay:false,..}));assert!(matches!(owner.commit(&p).unwrap(),Outcome::Committed{replay:true,..}));let delete=owner.prepare(&i,"platform-delete",&l.date,None,None).unwrap();assert!(matches!(owner.commit(&delete).unwrap(),Outcome::Committed{..}));owner.close();assert!(closed.load(Ordering::SeqCst));assert!(owner.commit(&p).is_err());}
 #[test]fn platform_owner_rollback_on_body_error_and_unwind(){let i=id();let closed=Arc::new(AtomicBool::new(false));let host=Host{store:ScratchStore::new_memory(&i).unwrap(),closed:closed.clone(),commit_fails:false,rollback_fails:false,commits:std::cell::Cell::new(0)};let mut owner=PlatformStore::qualified(Box::new(host));let r=owner.transaction::<()>(|_|Err(Error::Conflict));assert_eq!(r,Err(Error::Conflict));let unwind=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let _:Result<(),Error>=owner.transaction(|_|panic!("synthetic platform consumer panic"));}));assert!(unwind.is_err());let mut l=crate::track_log::CycleDayLog::new("2024-04-03");l.notes="synthetic".into();let l=c::CycleDayLog::from(&l);let p=owner.prepare(&i,"after-unwind",&l.date,Some(&l),None).unwrap();assert!(owner.commit(&p).is_ok());drop(owner);assert!(closed.load(Ordering::SeqCst));}
 #[test]fn rollback_failure_poisons_owner_before_reuse(){let i=id();let closed=Arc::new(AtomicBool::new(false));let host=Host{store:ScratchStore::new_memory(&i).unwrap(),closed:closed.clone(),commit_fails:false,rollback_fails:true,commits:std::cell::Cell::new(0)};let mut owner=PlatformStore::qualified(Box::new(host));assert_eq!(owner.transaction::<()>(|_|Err(Error::Conflict)),Err(Error::Conflict));assert!(owner.transaction(|_|Ok(())).is_err());drop(owner);assert!(closed.load(Ordering::SeqCst));}
 #[test]fn platform_commit_error_poisons_owner_even_after_commit(){let i=id();let closed=Arc::new(AtomicBool::new(false));let host=Host{store:ScratchStore::new_memory(&i).unwrap(),closed:closed.clone(),commit_fails:true,rollback_fails:false,commits:std::cell::Cell::new(0)};let mut owner=PlatformStore::qualified(Box::new(host));let mut l=crate::track_log::CycleDayLog::new("2024-04-03");l.notes="synthetic".into();let l=c::CycleDayLog::from(&l);let proposal=owner.prepare(&i,"platform-op",&l.date,Some(&l),None).unwrap();assert!(owner.commit(&proposal).is_err());assert!(owner.prepare(&i,"other-op",&l.date,Some(&l),None).is_err());drop(owner);assert!(closed.load(Ordering::SeqCst));}
}

/// Only connected PlatformStore implementation, plain in-memory SQLite.
/// Kept crate-private: external callers cannot wrap arbitrary connections.
pub(crate) struct ScratchConnection{store:ScratchStore}
impl ScratchConnection{pub(crate) fn new(i:&p::Identity)->Result<Self,Error>{Ok(Self{store:ScratchStore::new_memory(i)?})}}
impl crate::platform_record_store::Connection for ScratchConnection{
 fn begin(&self)->Result<(),Error>{self.store.db.conn.execute_batch("BEGIN IMMEDIATE")?;Ok(())}
 fn commit(&self)->Result<(),Error>{self.store.db.conn.execute_batch("COMMIT")?;Ok(())}
 fn rollback(&self)->Result<(),Error>{if self.store.db.conn.is_autocommit(){return Ok(())}self.store.db.conn.execute_batch("ROLLBACK")?;Ok(())}
 fn preparation(&self)->&dyn PrepareTransaction{&self.store}
 fn mutation(&self)->&dyn CommitTransaction{&self.store}
 fn log(&self,date:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{self.store.log(date)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.store.settings()}
 fn close(&mut self){}
}

impl crate::platform_record_io::Io for ScratchConnection{
 fn begin(&self)->Result<(),Error>{crate::platform_record_store::Connection::begin(self)}
 fn commit(&self)->Result<(),Error>{crate::platform_record_store::Connection::commit(self)}
 fn rollback(&self)->Result<(),Error>{crate::platform_record_store::Connection::rollback(self)}
 fn unresolved_conflicts(&self)->Result<i64,Error>{Ok(self.store.db.conn.query_row("SELECT count(*) FROM health_sync_conflicts WHERE ownerId=?1 AND resolvedAt IS NULL",[self.store.bound_identity()?.effective],|r|r.get(0))?)}
 fn active(&self)->bool{!self.store.db.conn.is_autocommit()}
 fn close(&mut self){}
 fn identity(&self)->Result<p::Identity,Error>{self.store.bound_identity()}
 fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>{CommitTransaction::receipt(&self.store,n,o)}
 fn revision(&self,r:&str)->Result<i64,Error>{self.store.revision(r)}
 fn physical(&self,r:&str)->Result<String,Error>{self.store.physical(r)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{self.store.sync_prior(o,r)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.store.settings()}
 fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>{Ok(self.store.db.get_profile()?)}
 fn write_profile(&self,p:&c::OnboardingProfile)->Result<(),Error>{self.store.db.insert_or_update_profile(p)?;Ok(())}
 fn log(&self,d:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{self.store.log(d)}
 fn write_log(&self,l:Option<&c::CycleDayLog>,r:&str)->Result<(),Error>{CommitTransaction::write_log(&self.store,l,r)}
 fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>{CommitTransaction::write_settings(&self.store,s)}
 fn write_revision(&self,r:&str,v:i64)->Result<(),Error>{CommitTransaction::write_revision(&self.store,r,v)}
 fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,p:Option<&str>)->Result<(),Error>{CommitTransaction::write_journal(&self.store,n,o,r,v,p)}
 fn write_sync(&self,i:&SyncInput,r:&str,k:usize,v:i64,s:i64,p:Option<&str>,h:&str)->Result<(),Error>{CommitTransaction::write_sync(&self.store,i,r,k,v,s,p,h)}
 fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>{CommitTransaction::write_receipt(&self.store,n,o,d,r)}
}

#[cfg(test)]mod typed_cell_oracle_tests{
 use super::*;use crate::platform_record_cells::{self as cells,Cell};
 fn row(store:&ScratchStore,table:&str)->Vec<Cell>{let mut stmt=store.db.conn.prepare(&format!("SELECT * FROM {table}")).unwrap();let count=stmt.column_count();let mut rows=stmt.query([]).unwrap();let Some(row)=rows.next().unwrap()else{return vec![]};(0..count).map(|n|match row.get_ref(n).unwrap(){ValueRef::Null=>Cell::Null,ValueRef::Integer(v)=>Cell::Integer(v),ValueRef::Real(v)=>Cell::Real(v),ValueRef::Text(v)=>Cell::Text(std::str::from_utf8(v).unwrap().into()),_=>panic!("unsupported oracle cell")}).collect()}
 #[test]fn actual_plain_sqlite_rows_decode_exact_source_models_and_physical_types(){let i=p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-instance".into(),epoch:1,session:1,generation:0,local:true};let s=ScratchStore::new_memory(&i).unwrap();let settings=c::CycleSettings::default();let profile=c::OnboardingProfile::default();s.db.save_settings(&settings).unwrap();s.db.insert_or_update_profile(&profile).unwrap();let mut log=crate::track_log::CycleDayLog::new("2024-04-03");log.notes="fixed synthetic α".into();log.bbt_celsius=36.6;s.db.insert_or_update_log(&log).unwrap();let sc=row(&s,"cycle_settings");let pc=row(&s,"onboarding_profiles");let lc=row(&s,"cycle_day_logs");assert_eq!(cells::settings(&sc).unwrap(),Some(settings));assert_eq!(cells::profile(&pc).unwrap(),Some(profile));assert_eq!(cells::log(&lc,None).unwrap(),Some(log));assert_eq!(cells::physical_row(&lc).unwrap(),s.physical_row("2024-04-03").unwrap());let mut invalid=sc;invalid[7]=Cell::Integer(2);assert!(cells::settings(&invalid).is_err());}
}

#[cfg(test)]mod onboarding_transaction_tests{
 use super::*;use crate::local_onboarding_transaction as o;
 impl o::Backend for ScratchStore{
 fn active(&self)->bool{!self.db.conn.is_autocommit()}fn matches(&self,i:&p::Identity)->Result<bool,Error>{self.matches(i)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.settings()}fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>{Ok(self.db.get_profile()?)}
 fn revision(&self,r:&str)->Result<i64,Error>{self.revision(r)}fn physical(&self,r:&str)->Result<String,Error>{self.physical(r)}fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>{CommitTransaction::receipt(self,n,o)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{self.sync_prior(o,r)}
 fn write_sync(&self,i:&SyncInput,r:&str,k:usize,v:i64,s:i64,p:Option<&str>,h:&str)->Result<(),Error>{CommitTransaction::write_sync(self,i,r,k,v,s,p,h)}
 fn write_profile(&self,p:&c::OnboardingProfile)->Result<(),Error>{self.db.insert_or_update_profile(p)?;Ok(())}fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>{self.db.save_settings(s)?;Ok(())}
 fn write_revision(&self,r:&str,v:i64)->Result<(),Error>{CommitTransaction::write_revision(self,r,v)}fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,p:Option<&str>)->Result<(),Error>{CommitTransaction::write_journal(self,n,o,r,v,p)}fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>{CommitTransaction::write_receipt(self,n,o,d,r)}
 }
 fn identity()->p::Identity{p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-process".into(),epoch:1,session:1,generation:0,local:true}}
 fn profile()->c::OnboardingProfile{let mut p=c::OnboardingProfile::default();p.is_completed=true;p.is_age_verified=true;p.is_terms_agreed=true;p.completed_at_timestamp=123;p.selected_goals_csv="TRACK".into();p.last_period_date_string="2024-04-03".into();p.cycle_length_days=29;p.period_duration_days=5;p.remind_fertile_window=true;p}
 #[test]fn atomic_completion_fullprofile_and_preserved_settings_rollback_and_receipt(){for fault in 1..=7{let i=identity();let s=ScratchStore::new_memory(&i).unwrap();let p=s.transaction(||o::prepare(&s,&i,"complete",&profile(),false)).unwrap();assert!(s.transaction(||o::commit(&s,&p,fault)).is_err());assert!(s.db.get_profile().unwrap().is_none());assert!(s.settings().unwrap().is_none());assert_eq!(s.revision("profile").unwrap(),0);assert_eq!(s.db.conn.query_row("SELECT count(*) FROM local_journal",[],|r|r.get::<_,i64>(0)).unwrap(),0);assert_eq!(s.db.conn.query_row("SELECT count(*) FROM local_receipts",[],|r|r.get::<_,i64>(0)).unwrap(),0);}
 let i=identity();let s=ScratchStore::new_memory(&i).unwrap();let mut before=c::CycleSettings::default();before.is_discreet_notification_mode=true;before.lock_timeout_minutes=37;before.pregnancy_due_date="local-only".into();s.db.save_settings(&before).unwrap();let p=s.transaction(||o::prepare(&s,&i,"complete",&profile(),false)).unwrap();assert!(matches!(s.transaction(||o::commit(&s,&p,0)).unwrap(),Outcome::Committed{replay:false,..}));assert!(matches!(s.transaction(||o::commit(&s,&p,0)).unwrap(),Outcome::Committed{replay:true,..}));assert_eq!(s.db.get_profile().unwrap().unwrap(),profile());let after=s.settings().unwrap().unwrap();assert!(after.is_onboarding_completed&&after.is_discreet_notification_mode);assert_eq!(after.lock_timeout_minutes,37);assert_eq!(after.pregnancy_due_date,"local-only");assert_eq!(after.average_cycle_length,29);assert_eq!(s.revision("settings").unwrap(),1);assert_eq!(s.db.conn.query_row("SELECT count(*) FROM local_receipts",[],|r|r.get::<_,i64>(0)).unwrap(),1);
 }
 fn input()->SyncInput{SyncInput{owner:"dummy-sync-owner".into(),generation:0,now_ms:456,operation_ids:vec!["profile-child".into(),"settings-child".into()]}}
 #[test]fn atomic_sync_completion_rolls_back_all_tables_at_every_cut_and_replays_once(){for fault in 1..=9{let i=identity();let s=ScratchStore::new_memory(&i).unwrap();let p=s.transaction(||o::prepare_with_sync(&s,&i,"complete",&profile(),false,Some(input()))).unwrap();assert!(s.transaction(||o::commit(&s,&p,fault)).is_err());assert!(s.db.get_profile().unwrap().is_none());assert!(s.settings().unwrap().is_none());for table in ["local_revisions","local_journal","local_receipts","health_sync_revisions","health_sync_outbox"]{assert_eq!(s.db.conn.query_row(&format!("SELECT count(*) FROM {table}"),[],|r|r.get::<_,i64>(0)).unwrap(),0);}}
 let i=identity();let s=ScratchStore::new_memory(&i).unwrap();s.db.conn.execute("INSERT INTO health_sync_revisions VALUES('dummy-sync-owner','profile',4,'old',0,3,8)",[]).unwrap();let p=s.transaction(||o::prepare_with_sync(&s,&i,"complete",&profile(),false,Some(input()))).unwrap();s.transaction(||o::commit(&s,&p,0)).unwrap();assert!(matches!(s.transaction(||o::commit(&s,&p,0)).unwrap(),Outcome::Committed{replay:true,..}));assert_eq!(s.sync_prior("dummy-sync-owner","profile").unwrap(),Some((5,8)));assert_eq!(s.sync_prior("dummy-sync-owner","settings").unwrap(),Some((1,0)));let mut stmt=s.db.conn.prepare("SELECT recordId,operationId,baseRevision,revision,payloadJson,consentGeneration,attempts,nextAttemptAt,serverReceipt FROM health_sync_outbox ORDER BY recordId").unwrap();let rows=stmt.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,i64>(2)?,r.get::<_,i64>(3)?,r.get::<_,String>(4)?,r.get::<_,i64>(5)?,r.get::<_,i64>(6)?,r.get::<_,i64>(7)?,r.get::<_,Option<String>>(8)?))).unwrap().collect::<Result<Vec<_>,_>>().unwrap();assert_eq!(rows.len(),2);assert_eq!(rows[0],("profile".into(),"profile-child".into(),8,5,c::profile_payload(&profile()),0,0,0,None));let set=s.settings().unwrap().unwrap();assert_eq!(rows[1],("settings".into(),"settings-child".into(),0,1,c::settings_payload(&set),0,0,0,None));}
 #[test]fn sync_prior_drift_overflow_and_bad_child_identity_reject_without_domainwrites(){let i=identity();let s=ScratchStore::new_memory(&i).unwrap();for n in 0..5{let mut input=input();match n{0=>input.operation_ids[1]=input.operation_ids[0].clone(),1=>input.operation_ids.pop().map(|_|()).unwrap(),2=>input.generation=1,3=>input.now_ms=-1,_=>input.owner=" ".into()};assert!(s.transaction(||o::prepare_with_sync(&s,&i,"complete",&profile(),false,Some(input))).is_err());}let p=s.transaction(||o::prepare_with_sync(&s,&i,"complete",&profile(),false,Some(input()))).unwrap();s.db.conn.execute("INSERT INTO health_sync_revisions VALUES('dummy-sync-owner','profile',1,'old',0,3,0)",[]).unwrap();assert_eq!(s.transaction(||o::commit(&s,&p,0)),Err(Error::Conflict));assert!(s.db.get_profile().unwrap().is_none());s.db.conn.execute("UPDATE health_sync_revisions SET revision=?1",[i64::MAX]).unwrap();assert!(s.transaction(||o::prepare_with_sync(&s,&i,"complete",&profile(),false,Some(input()))).is_err());}
 #[test]fn fullphysical_occ_lock_request_and_inactive_boundary_reject(){let i=identity();let s=ScratchStore::new_memory(&i).unwrap();assert!(o::prepare(&s,&i,"x",&profile(),false).is_err());assert!(s.transaction(||o::prepare(&s,&i,"x",&profile(),true)).is_err());let p=s.transaction(||o::prepare(&s,&i,"complete",&profile(),false)).unwrap();let mut changed=c::CycleSettings::default();changed.is_discreet_notification_mode=true;s.db.save_settings(&changed).unwrap();assert_eq!(s.transaction(||o::commit(&s,&p,0)),Err(Error::Conflict));assert!(s.db.get_profile().unwrap().is_none());let p=s.transaction(||o::prepare(&s,&i,"complete",&profile(),false)).unwrap();s.transaction(||o::commit(&s,&p,0)).unwrap();let mut changed=profile();changed.completed_at_timestamp=124;let q=s.transaction(||o::prepare(&s,&i,"complete",&changed,false)).unwrap();assert_eq!(s.transaction(||o::commit(&s,&q,0)),Err(Error::InvalidReuse));}
}

#[cfg(test)]mod startup_conflict_owner_tests{use super::*;#[test]fn conflict_snapshot_counts_only_unresolved_same_admitted_owner(){let i=p::Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-process".into(),epoch:1,session:1,generation:0,local:true};let io=ScratchConnection::new(&i).unwrap();for(owner,record,resolved)in [("dummy-owner","a",None),("dummy-owner","b",Some(5i64)),("other-owner","c",None)]{io.store.db.conn.execute("INSERT INTO health_sync_conflicts VALUES(?1,?2,'op',1,NULL,'UPSERT',2,NULL,0,0,?3,NULL)",params![owner,record,resolved]).unwrap();}let mut store=crate::platform_record_store::PlatformStore::synthetic_fixture(Box::new(io));let rows=store.startup_rows().unwrap();assert_eq!(rows.conflicts,1);assert_eq!(rows.local_state(),crate::account_start::LocalDataState::Conflicted);}}
