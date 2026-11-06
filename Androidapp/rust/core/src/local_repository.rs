//! Single-owner/session router over the real SQLite consumer, local integration
//! candidate only. No Android/FFI/account/consent inference or network capability.
//! Mutex exclusion covers identity routing, transaction and accepted read. Raw
//! stores/rows never escape except immutable identity-bound snapshots.
use crate::{local_commit_store::{ScratchStore,Prepared,SyncInput,Outcome,Error},local_commit_plan::Identity,sync_codec::CycleDayLog,track_log};
use std::{collections::BTreeMap,sync::Mutex};
pub struct Repository {state:Mutex<State>}
struct State {active:Identity,stores:BTreeMap<String,(String,ScratchStore)>}
#[derive(Clone,Debug)]
pub struct Token {identity:Identity}
#[derive(Clone,Debug)]
pub struct Snapshot {pub identity:Identity,pub log:Option<track_log::CycleDayLog>}
impl Repository {
 pub fn new_synthetic(identity:Identity,sync_owner:String)->Result<Self,Error>{identity.validate()?;let store=ScratchStore::new_memory(&identity)?;let mut stores=BTreeMap::new();stores.insert(identity.namespace.clone(),(sync_owner,store));Ok(Self{state:Mutex::new(State{active:identity,stores})})}
 pub fn capture(&self)->Result<Token,Error>{Ok(Token{identity:self.state.lock().map_err(|_|Error::Storage)?.active.clone()})}
 /// Explicit caller-supplied sync owner preserves guest namespace/effective ID
 /// separation. This function grants no consent and enables no sync release.
 pub fn prepare_save(&self,t:&Token,op:&str,log:&CycleDayLog,now:i64,child_ids:Vec<String>)->Result<Prepared,Error>{let s=self.state.lock().map_err(|_|Error::Storage)?;if t.identity!=s.active{return Err(Error::Stale)}let (owner,store)=s.stores.get(&s.active.namespace).ok_or(Error::Storage)?;store.prepare_save_sync(&t.identity,op,log,SyncInput{owner:owner.clone(),generation:t.identity.generation,now_ms:now,operation_ids:child_ids})}
 pub fn prepare_delete(&self,t:&Token,op:&str,date:&str,now:i64,child_ids:Vec<String>)->Result<Prepared,Error>{let s=self.state.lock().map_err(|_|Error::Storage)?;if t.identity!=s.active{return Err(Error::Stale)}let (owner,store)=s.stores.get(&s.active.namespace).ok_or(Error::Storage)?;store.prepare_delete_sync(&t.identity,op,date,SyncInput{owner:owner.clone(),generation:t.identity.generation,now_ms:now,operation_ids:child_ids})}
 pub fn commit(&self,t:&Token,p:&Prepared)->Result<Outcome,Error>{let s=self.state.lock().map_err(|_|Error::Storage)?;if t.identity!=s.active||p.plan().identity()!=&s.active{return Err(Error::Stale)}s.stores.get(&s.active.namespace).ok_or(Error::Storage)?.1.commit(p)}
 pub fn read(&self,t:&Token,date:&str)->Result<Snapshot,Error>{let s=self.state.lock().map_err(|_|Error::Storage)?;if t.identity!=s.active{return Err(Error::Stale)}Ok(Snapshot{identity:s.active.clone(),log:s.stores.get(&s.active.namespace).ok_or(Error::Storage)?.1.log(date)?})}
 pub fn accept(&self,snapshot:&Snapshot)->Result<bool,Error>{Ok(snapshot.identity==self.state.lock().map_err(|_|Error::Storage)?.active)}
 /// One exclusion domain for session/consent generation and owner changes.
 /// Android preference publication MUST be connected to this in future glue;
 /// this local candidate does not mutate existing AuthPreferences.
 pub fn transition(&self,t:&Token,next:Identity,sync_owner:String)->Result<Token,Error>{next.validate()?;let mut s=self.state.lock().map_err(|_|Error::Storage)?;if t.identity!=s.active||next.epoch<=s.active.epoch{return Err(Error::Stale)}
  if let Some((owner,store))=s.stores.get(&next.namespace){if *owner!=sync_owner{return Err(Error::InvalidSync)};let expected=if next.namespace==s.active.namespace{s.active.clone()}else{store.bound_identity()?};store.transition_bound(&expected,&next)?;}else{s.stores.insert(next.namespace.clone(),(sync_owner,ScratchStore::new_memory(&next)?));}
  s.active=next.clone();Ok(Token{identity:next})
 }
}
