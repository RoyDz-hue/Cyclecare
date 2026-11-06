//! Separate source-only atomic profile/settings protocol. Not V5 log receipts,
//! account preferences/PIN/backup enable or production migration. Caller owns
//! qualified connection transaction and current full account admission.
use crate::{local_commit_plan::Identity,local_transaction::{Error,Outcome},platform_record_cells as cells,sync_codec as c};
#[derive(Clone)]pub struct Prepared{identity:Identity,operation:String,profile:c::OnboardingProfile,settings:c::CycleSettings,bases:[(i64,String);2],digest:String,sync:Option<(crate::local_transaction::SyncInput,[Option<(i64,i64)>;2])>}
pub(crate) trait Backend{
 fn active(&self)->bool;fn matches(&self,i:&Identity)->Result<bool,Error>;
 fn settings(&self)->Result<Option<c::CycleSettings>,Error>;fn profile(&self)->Result<Option<c::OnboardingProfile>,Error>;
 fn revision(&self,r:&str)->Result<i64,Error>;fn physical(&self,r:&str)->Result<String,Error>;
 fn receipt(&self,n:&str,o:&str)->Result<Option<(String,String)>,Error>;
 fn write_profile(&self,p:&c::OnboardingProfile)->Result<(),Error>;fn write_settings(&self,s:&c::CycleSettings)->Result<(),Error>;
 fn write_revision(&self,r:&str,v:i64)->Result<(),Error>;fn write_journal(&self,n:&str,o:&str,r:&str,v:i64,p:Option<&str>)->Result<(),Error>;
 fn sync_prior(&self,_:&str,_:&str)->Result<Option<(i64,i64)>,Error>{Err(Error::InvalidSync)}
 fn write_sync(&self,_:&crate::local_transaction::SyncInput,_:&str,_:usize,_:i64,_:i64,_:Option<&str>,_:&str)->Result<(),Error>{Err(Error::InvalidSync)}
 fn write_receipt(&self,n:&str,o:&str,d:&str,r:&str)->Result<(),Error>;
}
fn full_profile(p:&c::OnboardingProfile)->Result<String,Error>{cells::physical_row(&cells::profile_row(p)?)}
fn full_settings(s:&c::CycleSettings)->Result<String,Error>{cells::physical_row(&cells::settings_row(s)?)}
fn operation(s:&str)->bool{!s.trim().is_empty()&&s.encode_utf16().count()<=128&&!s.contains('\0')}
pub(crate) fn prepare(db:&dyn Backend,i:&Identity,op:&str,profile:&c::OnboardingProfile,enable_lock:bool)->Result<Prepared,Error>{prepare_with_sync(db,i,op,profile,enable_lock,None)}
pub(crate) fn prepare_with_sync(db:&dyn Backend,i:&Identity,op:&str,profile:&c::OnboardingProfile,enable_lock:bool,sync:Option<crate::local_transaction::SyncInput>)->Result<Prepared,Error>{
 i.validate()?;if !db.active()||!db.matches(i)?{return Err(Error::Stale)}if !operation(op)||profile.id!=1||!profile.is_completed||!profile.is_age_verified||!profile.is_terms_agreed||profile.completed_at_timestamp<0{return Err(Error::Storage)}
 // Lock/PIN is an independent platform effect, never silently enabled here.
 if enable_lock{return Err(Error::Storage)}full_profile(profile)?;
 let mut settings=db.settings()?.unwrap_or_default();settings.is_onboarding_completed=true;settings.selected_goals_csv=profile.selected_goals_csv.clone();settings.last_period_start_date=profile.last_period_date_string.clone();settings.average_cycle_length=profile.cycle_length_days;settings.period_duration=profile.period_duration_days;settings.remind_fertile=profile.remind_fertile_window;settings.is_app_lock_enabled=false;full_settings(&settings)?;
 let mut bases=[(0,String::new()),(0,String::new())];for(n,r)in ["profile","settings"].iter().enumerate(){let rev=db.revision(r)?;if rev<0||rev==i64::MAX{return Err(Error::Overflow)}bases[n]=(rev,db.physical(r)?);}
 let sync=if let Some(input)=sync{if !operation(&input.owner)||input.generation!=i.generation||input.now_ms<0||input.operation_ids.len()!=2||input.operation_ids.iter().any(|x|!operation(x))||input.operation_ids[0]==input.operation_ids[1]{return Err(Error::InvalidSync)}let mut priors=[None,None];for(n,r)in ["profile","settings"].iter().enumerate(){let prior=db.sync_prior(&input.owner,r)?;if prior.is_some_and(|(v,s)|v<0||s<0||v==i64::MAX){return Err(Error::Overflow)}priors[n]=prior;}Some((input,priors))}else{None};
 let digest=c::sha256_hex(&serde_json::json!(["local-onboarding-v1",[&i.namespace,&i.effective,&i.instance],i.epoch,i.session,i.generation,i.local,op,full_profile(profile)?,full_settings(&settings)?]).to_string());
 let digest=if let Some((input,priors))=&sync{c::sha256_hex(&serde_json::json!(["onboarding-sync-v1",digest,&input.owner,input.generation,input.now_ms,&input.operation_ids,priors]).to_string())}else{digest};
 Ok(Prepared{identity:i.clone(),operation:op.into(),profile:profile.clone(),settings,bases,digest,sync})
}
fn receipt(p:&Prepared)->Result<String,Error>{if let Some((input,priors))=&p.sync{return Ok(serde_json::json!(["onboarding-sync-v1","Committed",["profile",p.bases[0].0.checked_add(1).ok_or(Error::Overflow)?,full_profile(&p.profile)?,priors[0].map_or(0,|x|x.0).checked_add(1).ok_or(Error::Overflow)?,priors[0].map_or(0,|x|x.1),&input.operation_ids[0]],["settings",p.bases[1].0.checked_add(1).ok_or(Error::Overflow)?,full_settings(&p.settings)?,priors[1].map_or(0,|x|x.0).checked_add(1).ok_or(Error::Overflow)?,priors[1].map_or(0,|x|x.1),&input.operation_ids[1]]]).to_string())}Ok(serde_json::json!(["local-onboarding-v1","Committed",["profile",p.bases[0].0.checked_add(1).ok_or(Error::Overflow)?,full_profile(&p.profile)?],["settings",p.bases[1].0.checked_add(1).ok_or(Error::Overflow)?,full_settings(&p.settings)?]]).to_string())}
pub(crate) fn commit(db:&dyn Backend,p:&Prepared,fault:usize)->Result<Outcome,Error>{
 if !db.active()||!db.matches(&p.identity)?{return Err(Error::Stale)}let expected=receipt(p)?;
 if let Some((digest,result))=db.receipt(&p.identity.namespace,&p.operation)?{if digest!=p.digest{return Err(Error::InvalidReuse)}if result!=expected{return Err(Error::Storage)}return Ok(Outcome::Committed{replay:true,receipt:result})}
 for(n,r)in ["profile","settings"].iter().enumerate(){if db.revision(r)?!=p.bases[n].0||db.physical(r)?!=p.bases[n].1{return Err(Error::Conflict)}}
 if let Some((input,priors))=&p.sync{for(n,r)in ["profile","settings"].iter().enumerate(){if db.sync_prior(&input.owner,r)?!=priors[n]{return Err(Error::Conflict)}}}
 let mut step=0;let mut cut=||{step+=1;if step==fault{Err(Error::Storage)}else{Ok(())}};
 db.write_profile(&p.profile)?;cut()?;db.write_settings(&p.settings)?;cut()?;
 for(n,(r,payload))in [("profile",c::profile_payload(&p.profile)),("settings",c::settings_payload(&p.settings))].iter().enumerate(){let rev=p.bases[n].0+1;db.write_revision(r,rev)?;cut()?;db.write_journal(&p.identity.namespace,&p.operation,r,rev,Some(payload))?;cut()?;if let Some((input,priors))=&p.sync{let prior=priors[n].unwrap_or((0,0));db.write_sync(input,r,n,prior.0.checked_add(1).ok_or(Error::Overflow)?,prior.1,Some(payload),&c::hash(Some(payload)))?;cut()?;}}
 if db.profile()?.as_ref()!=Some(&p.profile)||db.settings()?.as_ref()!=Some(&p.settings){return Err(Error::Storage)}
 db.write_receipt(&p.identity.namespace,&p.operation,&p.digest,&expected)?;cut()?;Ok(Outcome::Committed{replay:false,receipt:expected})
}
