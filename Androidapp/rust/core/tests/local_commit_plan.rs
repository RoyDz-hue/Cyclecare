use cyclecare_core::{local_commit_plan::*,sync_codec::{self as c,CycleDayLog,CycleSettings}};
use serde_json::Value;
fn identity()->Identity{Identity{namespace:"owner-A".into(),effective:"effective-A".into(),instance:"db-A".into(),epoch:1,session:1,generation:0,local:true}}
fn log()->CycleDayLog{(&cyclecare_core::track_log::CycleDayLog::new("2024-03-17")).into()}
fn payload_log(payload:&str)->CycleDayLog{
 let p:Value=serde_json::from_str(payload).unwrap();let s=|k:&str|p[k].as_str().unwrap().to_string();
 CycleDayLog{date:s("date"),flow:s("flow"),energy:s("energy"),mood:s("mood"),symptoms:s("symptoms"),vaginal_health:s("vaginal_health"),urinary_health:s("urinary_health"),cervical_mucus:s("cervical_mucus"),ovulation_test:s("ovulation_test"),bbt_celsius:p["bbt_celsius"].as_f64().unwrap() as f32,sleep_hours:p["sleep_hours"].as_f64().unwrap() as f32,sleep_quality:s("sleep_quality"),water_glasses:p["water_glasses"].as_i64().unwrap() as i32,notes:s("notes"),pain_intensity:p["pain_intensity"].as_i64().unwrap() as i32}
}
#[test]
fn executed_kotlin_aosp_full_field_oracle(){
 let input:Value=serde_json::from_str(include_str!("fixtures/local_commit_track_oracle.json")).unwrap();let id=identity();let mut matched=0;let mut empty=0;let mut anchors=0;
 for case in input["cases"].as_array().unwrap(){
  let l=payload_log(case["payload"].as_str().unwrap());let mut settings=CycleSettings::default();settings.selected_goals_csv="cycle".into();settings.last_period_start_date=case["anchorBefore"].as_str().unwrap().into();
  let result=save_track(&id,&id,"corpus",&l,7,&settings,13);
  if !case["recorded"].as_bool().unwrap(){assert_eq!(result,Err(PlanError::Empty));empty+=1;continue}
  let plan=result.unwrap();let m=&plan.mutations()[0];assert_eq!(m.payload.as_deref(),case["payload"].as_str());assert_eq!(m.payload_hash,case["hash"].as_str().unwrap());assert_eq!(m.bbt_bits,Some(l.bbt_celsius.to_bits()));assert_eq!(m.sleep_bits,Some(l.sleep_hours.to_bits()));assert_eq!(m.expected_revision,7);
  assert_eq!(plan.reads()[0],ReadDependency{record:"settings".into(),revision:13,payload_hash:c::hash(Some(&c::settings_payload(&settings)))});
  if !case["anchorAfter"].is_null(){anchors+=1;assert_eq!(plan.mutations().len(),2);assert_eq!(plan.mutations()[1].expected_revision,13);assert_eq!(plan.mutations()[1].payload.as_deref(),case["settingsPayloadAfter"].as_str());}else{assert_eq!(plan.mutations().len(),1)}
  assert_eq!(plan.intent_digest(),c::sha256_hex(&plan.canonical_intent()));matched+=1;
 }
 assert_eq!(matched+empty,33);println!("Kotlin/AOSP cases: {matched} exact plans, {empty} Empty rejections, {anchors} atomic anchor plans");
 let deleted=delete_track(&id,&id,"delete","2024-03-16",1).unwrap();assert_eq!(deleted.mutations()[0].payload_hash,input["deleteHash"].as_str().unwrap());assert_eq!(deleted.mutations()[0].payload,None);assert!(deleted.reads().is_empty());
}
#[test]
fn every_identity_field_is_bound_and_stale(){
 let id=identity();let mut l=log();l.notes="x".into();let s=CycleSettings::default();let base=save_track(&id,&id,"op",&l,0,&s,0).unwrap();
 let mut variants=vec![];for field in 0..7{let mut n=id.clone();match field{0=>n.namespace+="B",1=>n.effective+="B",2=>n.instance+="B",3=>n.epoch+=1,4=>n.session+=1,5=>n.generation+=1,_=>n.local=false};variants.push(n)}
 for n in variants{assert_eq!(save_track(&id,&n,"op",&l,0,&s,0),Err(PlanError::Stale));assert_ne!(save_track(&n,&n,"op",&l,0,&s,0).unwrap().intent_digest(),base.intent_digest())}
 let mut aba=id.clone();aba.epoch+=2;assert_eq!(delete_track(&id,&aba,"old","2024-03-17",0),Err(PlanError::Stale));
}
#[test]
fn signedzero_intmin_and_finite_negative_preserved(){
 let id=identity();let mut l=log();l.notes="x".into();l.bbt_celsius=-0.0;l.sleep_hours=-3.5;l.water_glasses=i32::MIN;l.pain_intensity=i32::MAX;
 let p=save_track(&id,&id,"op",&l,0,&CycleSettings::default(),0).unwrap();assert_eq!(p.mutations()[0].bbt_bits,Some(0x80000000));assert_eq!(p.mutations()[0].payload.as_deref(),Some(c::log_payload(&l).as_str()));
 let mut z=l.clone();z.bbt_celsius=0.0;assert_ne!(p.intent_digest(),save_track(&id,&id,"op",&z,0,&CycleSettings::default(),0).unwrap().intent_digest());
 for value in [f32::NAN,f32::INFINITY,f32::NEG_INFINITY]{l.bbt_celsius=value;assert_eq!(save_track(&id,&id,"op",&l,0,&CycleSettings::default(),0),Err(PlanError::NonFinite))}
}
#[test]
fn checked_limits_and_no_panics(){
 let id=identity();let mut l=log();l.notes="x".into();let s=CycleSettings::default();
 for op in ["", "\u{00a0}", &"x".repeat(129)]{assert_eq!(save_track(&id,&id,op,&l,0,&s,0),Err(PlanError::Operation))}
 for date in ["bad","2024-02-30","2024-2-01"]{assert_eq!(delete_track(&id,&id,"op",date,0),Err(PlanError::Date))}
 for date in ["0000-01-01","9999-12-31","+10000-01-01","-0001-01-01","-999999999-01-01","+999999999-12-31"]{assert!(delete_track(&id,&id,"op",date,0).is_ok())}
 assert_eq!(save_track(&id,&id,"op",&l,-1,&s,0),Err(PlanError::Revision));assert_eq!(save_track(&id,&id,"op",&l,0,&s,-1),Err(PlanError::Revision));assert_eq!(delete_track(&id,&id,"op",&l.date,i64::MAX),Err(PlanError::Overflow));
 l.notes="😀".repeat(2048);assert!(save_track(&id,&id,"op",&l,0,&s,0).is_ok());l.notes.push('x');assert_eq!(save_track(&id,&id,"op",&l,0,&s,0),Err(PlanError::Text));
 for f in 0..6{let mut n=id.clone();match f{0=>n.namespace=" ".into(),1=>n.effective="😀".repeat(65),2=>n.instance="".into(),3=>n.epoch=-1,4=>n.session=-1,_=>n.generation=-1};assert_eq!(delete_track(&n,&n,"op","2024-03-01",0),Err(PlanError::Identity));}
}
#[test]
fn anchor_strict_boundary_and_read_dependency_even_without_write(){
 let id=identity();let mut l=log();l.notes="x".into();l.flow="LIGHT".into();let mut s=CycleSettings::default();s.last_period_start_date="2024-03-01".into();
 l.date="2024-03-16".into();let p=save_track(&id,&id,"op",&l,0,&s,1).unwrap();assert_eq!(p.mutations().len(),1);assert_eq!(p.reads().len(),1);
 l.date="2024-03-17".into();assert_eq!(save_track(&id,&id,"op",&l,0,&s,1).unwrap().mutations().len(),2);assert_eq!(save_track(&id,&id,"op",&l,0,&s,i64::MAX),Err(PlanError::Overflow));
 l.flow="SPOTTING".into();assert_eq!(save_track(&id,&id,"op",&l,0,&s,i64::MAX).unwrap().mutations().len(),1);
 let a=save_track(&id,&id,"op",&l,0,&s,1).unwrap();let b=save_track(&id,&id,"op",&l,0,&s,2).unwrap();assert_ne!(a.intent_digest(),b.intent_digest());s.last_period_start_date="2024-03-02".into();assert_ne!(a.intent_digest(),save_track(&id,&id,"op",&l,0,&s,1).unwrap().intent_digest());
}
#[test]
fn transaction_rejects_stale_unchanged_settings_before_any_effect(){
 struct View{ id:Identity, settings:c::CycleSettings, sr:i64, lr:i64 }
 impl TransactionView for View{
  fn current_identity(&self)->&Identity{&self.id}
  fn revision(&self,r:&str)->i64{if r=="settings"{self.sr}else{self.lr}}
  fn payload_hash(&self,r:&str)->String{assert_eq!(r,"settings");c::hash(Some(&c::settings_payload(&self.settings)))}
 }
 let id=identity();let mut l=log();l.notes="x".into();let s=CycleSettings::default();let p=save_track(&id,&id,"op",&l,2,&s,4).unwrap();assert_eq!(p.mutations().len(),1);
 let mut v=View{id:id.clone(),settings:s,sr:4,lr:2};assert_eq!(p.validate_transaction_view(&v),Ok(()));
 v.sr=5;assert_eq!(p.validate_transaction_view(&v),Err(PlanError::Conflict));v.sr=4;v.settings.average_cycle_length+=1;assert_eq!(p.validate_transaction_view(&v),Err(PlanError::Conflict));v.settings.average_cycle_length-=1;v.lr=3;assert_eq!(p.validate_transaction_view(&v),Err(PlanError::Conflict));v.lr=2;v.id.session+=1;assert_eq!(p.validate_transaction_view(&v),Err(PlanError::Stale));
}
#[test]
fn empty_and_intent_mutation_domains(){
 let id=identity();let mut l=log();let s=CycleSettings::default();l.bbt_celsius=-1.0;l.sleep_hours=-0.0;assert_eq!(save_track(&id,&id,"op",&l,0,&s,0),Err(PlanError::Empty));l.notes="x".into();
 let p=save_track(&id,&id,"op",&l,0,&s,0).unwrap();assert_ne!(p.intent_digest(),save_track(&id,&id,"op2",&l,0,&s,0).unwrap().intent_digest());assert_ne!(p.intent_digest(),save_track(&id,&id,"op",&l,1,&s,0).unwrap().intent_digest());assert_ne!(p.intent_digest(),delete_track(&id,&id,"op",&l.date,0).unwrap().intent_digest());
}
#[test]
fn aosp_candidate_v2_intent_known_answer(){
 let expected:Value=serde_json::from_str(include_str!("fixtures/local_commit_intent_v2.json")).unwrap();let id=identity();let p=delete_track(&id,&id,"op","2024-03-17",0).unwrap();assert_eq!(p.canonical_intent(),expected["canonical"].as_str().unwrap());assert_eq!(p.intent_digest(),expected["digest"].as_str().unwrap());
}
