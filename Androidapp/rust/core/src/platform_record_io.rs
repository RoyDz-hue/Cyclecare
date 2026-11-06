//! Typed connection bridge for a future opaque platform DB owner. Does not
//! implement SQL/schema/key handling and is not selected by an application.
//! No digest/plan/receipt computation is delegated across this boundary.
use crate::{local_transaction::{Error,SyncInput,PrepareTransaction,CommitTransaction},local_commit_plan::Identity,sync_codec as c,track_log,platform_record_store::Connection};
pub trait Io:Send{
 fn begin(&self)->Result<(),Error>;fn commit(&self)->Result<(),Error>;fn rollback(&self)->Result<(),Error>;fn active(&self)->bool;fn close(&mut self);
 fn identity(&self)->Result<Identity,Error>;
 fn receipt(&self,namespace:&str,operation:&str)->Result<Option<(String,String)>,Error>;
 fn revision(&self,record:&str)->Result<i64,Error>;
 fn physical(&self,record:&str)->Result<String,Error>;
 fn sync_prior(&self,owner:&str,record:&str)->Result<Option<(i64,i64)>,Error>;
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>;
 fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>;
 fn unresolved_conflicts(&self)->Result<i64,Error>{Err(Error::Storage)}
 fn log(&self,date:&str)->Result<Option<track_log::CycleDayLog>,Error>;
 fn write_log(&self,log:Option<&c::CycleDayLog>,record:&str)->Result<(),Error>;
 fn write_settings(&self,settings:&c::CycleSettings)->Result<(),Error>;
 fn write_profile(&self,_:&c::OnboardingProfile)->Result<(),Error>{Err(Error::Storage)}
 fn write_revision(&self,record:&str,revision:i64)->Result<(),Error>;
 fn write_journal(&self,namespace:&str,operation:&str,record:&str,revision:i64,payload:Option<&str>)->Result<(),Error>;
 fn write_sync(&self,input:&SyncInput,record:&str,index:usize,revision:i64,server:i64,payload:Option<&str>,hash:&str)->Result<(),Error>;
 fn write_receipt(&self,namespace:&str,operation:&str,digest:&str,receipt:&str)->Result<(),Error>;
}
pub(crate) struct Bridge{io:Box<dyn Io>}
impl Bridge{pub(crate) fn new(io:Box<dyn Io>)->Self{Self{io}}}
impl PrepareTransaction for Bridge{
 fn transaction_active(&self)->bool{self.io.active()}
 fn matches(&self,i:&Identity)->Result<bool,Error>{Ok(self.io.identity()?==*i)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.io.settings()}
 fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>{self.io.profile()}
 fn revision(&self,r:&str)->Result<i64,Error>{self.io.revision(r)}
 fn physical(&self,r:&str)->Result<String,Error>{self.io.physical(r)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{self.io.sync_prior(o,r)}
}
impl CommitTransaction for Bridge{
 fn transaction_active(&self)->bool{self.io.active()}
 fn matches(&self,i:&Identity)->Result<bool,Error>{Ok(self.io.identity()?==*i)}
 fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>{self.io.receipt(n,o)}
 fn revision(&self,r:&str)->Result<i64,Error>{self.io.revision(r)}
 fn physical(&self,r:&str)->Result<String,Error>{self.io.physical(r)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{self.io.sync_prior(o,r)}
 fn write_log(&self,l:Option<&c::CycleDayLog>,r:&str)->Result<(),Error>{self.io.write_log(l,r)}
 fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>{self.io.write_settings(s)}
 fn write_revision(&self,r:&str,v:i64)->Result<(),Error>{self.io.write_revision(r,v)}
 fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,p:Option<&str>)->Result<(),Error>{self.io.write_journal(n,o,r,v,p)}
 fn write_sync(&self,i:&SyncInput,r:&str,k:usize,v:i64,s:i64,p:Option<&str>,h:&str)->Result<(),Error>{self.io.write_sync(i,r,k,v,s,p,h)}
 fn log(&self,d:&str)->Result<Option<track_log::CycleDayLog>,Error>{self.io.log(d)}
 fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>{self.io.write_receipt(n,o,d,r)}
}
impl Connection for Bridge{
 fn begin(&self)->Result<(),Error>{self.io.begin()}fn commit(&self)->Result<(),Error>{self.io.commit()}fn rollback(&self)->Result<(),Error>{self.io.rollback()}
 fn onboarding(&self)->Option<&dyn crate::local_onboarding_transaction::Backend>{Some(self)}
 fn preparation(&self)->&dyn PrepareTransaction{self}fn mutation(&self)->&dyn CommitTransaction{self}
 fn log(&self,d:&str)->Result<Option<track_log::CycleDayLog>,Error>{self.io.log(d)}
 fn unresolved_conflicts(&self)->Result<i64,Error>{self.io.unresolved_conflicts()}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.io.settings()}fn close(&mut self){self.io.close()}
}

impl crate::local_onboarding_transaction::Backend for Bridge{
 fn active(&self)->bool{self.io.active()}fn matches(&self,i:&Identity)->Result<bool,Error>{Ok(self.io.identity()?==*i)}
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>{self.io.settings()}fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>{self.io.profile()}
 fn revision(&self,r:&str)->Result<i64,Error>{self.io.revision(r)}fn physical(&self,r:&str)->Result<String,Error>{self.io.physical(r)}fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>{self.io.receipt(n,o)}
 fn sync_prior(&self,o:&str,r:&str)->Result<Option<(i64,i64)>,Error>{self.io.sync_prior(o,r)}
 fn write_sync(&self,i:&SyncInput,r:&str,k:usize,v:i64,s:i64,p:Option<&str>,h:&str)->Result<(),Error>{self.io.write_sync(i,r,k,v,s,p,h)}
 fn write_profile(&self,p:&c::OnboardingProfile)->Result<(),Error>{self.io.write_profile(p)}fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>{self.io.write_settings(s)}fn write_revision(&self,r:&str,v:i64)->Result<(),Error>{self.io.write_revision(r,v)}fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,p:Option<&str>)->Result<(),Error>{self.io.write_journal(n,o,r,v,p)}fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>{self.io.write_receipt(n,o,d,r)}
}
