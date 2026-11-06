//! Owner of one qualified platform connection for the existing V5 protocol.
//! Source-prepared only. No factory opens a platform DB in current app wiring.
use crate::{local_transaction::{self as t,Error,Outcome,Prepared,SyncInput,PrepareTransaction,CommitTransaction},local_commit_plan::Identity,sync_codec::CycleDayLog};
/// All calls target the SAME leased, qualified connection. No raw SQL/connection
/// escapes. Finish must report commit uncertainty rather than silently retry.
pub(crate) trait Connection:Send {
 fn begin(&self)->Result<(),Error>;
 fn commit(&self)->Result<(),Error>;
 fn rollback(&self)->Result<(),Error>;
 fn onboarding(&self)->Option<&dyn crate::local_onboarding_transaction::Backend>{None}
 fn preparation(&self)->&dyn PrepareTransaction;
 fn mutation(&self)->&dyn CommitTransaction;
 fn log(&self,date:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>;
 fn settings(&self)->Result<Option<crate::sync_codec::CycleSettings>,Error>;
 fn unresolved_conflicts(&self)->Result<i64,Error>{Err(Error::Storage)}
 fn close(&mut self);
}
pub struct PlatformStore{connection:Box<dyn Connection>,closed:bool,uncertain:std::cell::Cell<bool>}
impl PlatformStore{
 #[cfg(feature="test-support")]pub fn synthetic_fixture(io:Box<dyn crate::SyntheticRecordIo>)->Self{Self::qualified(Box::new(crate::platform_record_io::Bridge::new(io)))}
 #[cfg(feature="sqlite")]pub fn synthetic_memory(i:&Identity)->Result<Self,Error>{Ok(Self::qualified(Box::new(crate::platform_record_io::Bridge::new(Box::new(crate::local_commit_store::ScratchConnection::new(i)?)))))}
 /// Caller supplies only a schema/key/lifetime-qualified private connection.
 pub(crate) fn qualified(connection:Box<dyn Connection>)->Self{Self{connection,closed:false,uncertain:std::cell::Cell::new(false)}}
 pub(crate) fn transaction<T>(&mut self,action:impl FnOnce(&dyn Connection)->Result<T,Error>)->Result<T,Error>{
  if self.closed||self.uncertain.get(){return Err(Error::Storage)}
  struct Rollback<'a>{connection:&'a dyn Connection,armed:bool,uncertain:&'a std::cell::Cell<bool>}
  impl Drop for Rollback<'_>{fn drop(&mut self){if self.armed{if self.connection.rollback().is_err(){self.uncertain.set(true);}}}}
  self.connection.begin()?;let mut guard=Rollback{connection:self.connection.as_ref(),armed:true,uncertain:&self.uncertain};let value=action(guard.connection)?;
  // A failed commit may have taken effect. Poison this owner until separately
  // recovered; never prepare/reissue another mutation against uncertain truth.
  if guard.connection.commit().is_err(){self.uncertain.set(true);return Err(Error::Storage)}guard.armed=false;Ok(value)
 }
 /// Separate protocol, never V5 log prepared/receipt. Profile/settings
 /// completion only: PIN/preferences/backup remain separate gates.
 pub fn prepare_onboarding(&mut self,i:&Identity,op:&str,profile:&crate::sync_codec::OnboardingProfile,enable_lock:bool)->Result<crate::local_onboarding_transaction::Prepared,Error>{self.transaction(|c|crate::local_onboarding_transaction::prepare(c.onboarding().ok_or(Error::Storage)?,i,op,profile,enable_lock))}
 /// Explicit journal input records local pending sync intent, never upload or consent.
 pub fn prepare_onboarding_sync(&mut self,i:&Identity,op:&str,profile:&crate::sync_codec::OnboardingProfile,enable_lock:bool,sync:SyncInput)->Result<crate::local_onboarding_transaction::Prepared,Error>{self.transaction(|c|crate::local_onboarding_transaction::prepare_with_sync(c.onboarding().ok_or(Error::Storage)?,i,op,profile,enable_lock,Some(sync)))}
 pub fn commit_onboarding(&mut self,p:&crate::local_onboarding_transaction::Prepared)->Result<Outcome,Error>{self.transaction(|c|crate::local_onboarding_transaction::commit(c.onboarding().ok_or(Error::Storage)?,p,0))}
 pub fn prepare(&mut self,i:&Identity,op:&str,date:&str,log:Option<&CycleDayLog>,sync:Option<SyncInput>)->Result<Prepared,Error>{self.transaction(|c|t::prepare_transaction(c.preparation(),i,op,date,log,sync))}
 pub fn commit(&mut self,p:&Prepared)->Result<Outcome,Error>{self.transaction(|c|t::commit_transaction(c.mutation(),p,0))}
 pub fn log(&mut self,date:&str)->Result<Option<crate::track_log::CycleDayLog>,Error>{self.transaction(|c|c.log(date))}
 /// Profile/settings/conflict state observed in ONE connection transaction.
 /// Failure is never converted to emptyprofile/defaultsettings/Clean.
 pub fn startup_rows(&mut self)->Result<StartupRows,Error>{self.transaction(|c|{let profile=c.preparation().profile()?;let settings=c.settings()?;let conflicts=c.unresolved_conflicts()?;if conflicts<0{return Err(Error::Storage)}Ok(StartupRows{profile,settings,conflicts})})}
 pub fn profile(&mut self)->Result<Option<crate::sync_codec::OnboardingProfile>,Error>{self.transaction(|c|c.preparation().profile())}
 pub fn settings(&mut self)->Result<Option<crate::sync_codec::CycleSettings>,Error>{self.transaction(|c|c.settings())}
 pub fn close(&mut self){if !self.closed{self.connection.close();self.closed=true}}
}
impl Drop for PlatformStore{fn drop(&mut self){self.close()}}

#[cfg(test)]mod onboarding_owner_tests{use super::*;#[test]fn one_owned_connection_completes_then_reads_fullprofile_and_preserves_v5(){let i=Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-process".into(),epoch:1,session:1,generation:0,local:true};let mut s=PlatformStore::synthetic_memory(&i).unwrap();let mut p=crate::sync_codec::OnboardingProfile::default();p.is_completed=true;p.is_age_verified=true;p.is_terms_agreed=true;p.completed_at_timestamp=123;p.last_period_date_string="2024-04-03".into();let prepared=s.prepare_onboarding(&i,"complete",&p,false).unwrap();assert!(matches!(s.commit_onboarding(&prepared).unwrap(),Outcome::Committed{replay:false,..}));assert!(matches!(s.commit_onboarding(&prepared).unwrap(),Outcome::Committed{replay:true,..}));assert_eq!(s.profile().unwrap(),Some(p));assert!(s.settings().unwrap().unwrap().is_onboarding_completed);let mut row=crate::track_log::CycleDayLog::new("2024-04-04");row.notes="fixed dummy".into();let wire=crate::sync_codec::CycleDayLog::from(&row);let log=s.prepare(&i,"log-after-complete",&wire.date,Some(&wire),None).unwrap();assert!(s.commit(&log).is_ok());assert_eq!(s.log(&wire.date).unwrap(),Some(row));s.close();assert!(s.commit_onboarding(&prepared).is_err());}}

#[derive(Clone)]pub struct StartupRows{pub profile:Option<crate::sync_codec::OnboardingProfile>,pub settings:Option<crate::sync_codec::CycleSettings>,pub conflicts:i64}
impl StartupRows{pub fn local_valid(&self)->bool{self.profile.as_ref().is_some_and(|p|p.is_completed)&&self.settings.as_ref().is_some_and(|s|s.is_onboarding_completed)}pub fn local_state(&self)->crate::account_start::LocalDataState{if self.conflicts>0{crate::account_start::LocalDataState::Conflicted}else{crate::account_start::LocalDataState::Clean}}}

#[cfg(test)]mod startup_rows_tests{use super::*;
 #[test]fn owned_snapshot_is_missing_not_defaults_then_sees_complete_profile_and_settings(){let i=Identity{namespace:"synthetic-only".into(),effective:"dummy-owner".into(),instance:"dummy-process".into(),epoch:1,session:1,generation:0,local:true};let mut store=PlatformStore::synthetic_memory(&i).unwrap();let empty=store.startup_rows().unwrap();assert!(empty.profile.is_none()&&empty.settings.is_none()&&!empty.local_valid());assert_eq!(empty.local_state(),crate::account_start::LocalDataState::Clean);let mut profile=crate::sync_codec::OnboardingProfile::default();profile.is_completed=true;profile.is_age_verified=true;profile.is_terms_agreed=true;profile.completed_at_timestamp=123;let prepared=store.prepare_onboarding(&i,"complete",&profile,false).unwrap();store.commit_onboarding(&prepared).unwrap();let rows=store.startup_rows().unwrap();assert!(rows.local_valid());assert_eq!(rows.profile,Some(profile));store.close();assert!(store.startup_rows().is_err());}
 #[test]fn conflicts_never_erase_local_validity_or_masquerade_as_clean(){let mut rows=StartupRows{profile:None,settings:None,conflicts:2};assert_eq!(rows.local_state(),crate::account_start::LocalDataState::Conflicted);assert!(!rows.local_valid());let mut profile=crate::sync_codec::OnboardingProfile::default();profile.is_completed=true;let mut settings=crate::sync_codec::CycleSettings::default();settings.is_onboarding_completed=true;rows.profile=Some(profile);rows.settings=Some(settings);assert!(rows.local_valid());assert_eq!(rows.local_state(),crate::account_start::LocalDataState::Conflicted);}
}
