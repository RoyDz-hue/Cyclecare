mod cycle_math; mod kotlin_text; mod sync_codec; mod track; mod care_policy; mod log_parser; mod care; mod settings;
use serde_json::{Value,json};
use sha2::{Sha256,Digest};
#[derive(Clone,Debug,PartialEq)]
struct Identity { namespace:String, effective:String, instance:String, epoch:u64, session:u64, generation:u64, local:bool }
#[derive(Clone)] struct Draft { identity:Identity, date:String, notes:String, bbt:f32, sleep:f32 }
fn edit(i:&Identity,date:&str,notes:&str,bbt:f32,sleep:f32)->Result<Draft,&'static str>{
 if !bbt.is_finite() || !sleep.is_finite(){return Err("NonFinite")}
 if cycle_math::parse_local_date_iso(date).is_none(){return Err("InvalidDate")}
 Ok(Draft{identity:i.clone(),date:date.into(),notes:notes.into(),bbt,sleep})
}
fn confirm(d:&mut Option<Draft>,i:&Identity,op:&str,base:u64)->Result<Value,&'static str>{
 let draft=d.take().ok_or("NoDraft")?;
 if draft.identity!=*i{return Err("Stale")}
 // This slice supports notes/BBT/sleep only; other fields are NOT silently dropped.
 if !draft.notes.chars().any(|c|!kotlin_text::kotlin_is_whitespace(c)) && !(draft.bbt>0.0 || draft.sleep>0.0){return Err("Empty")}
 Ok(json!({"namespace":i.namespace,"effective":i.effective,"instance":i.instance,"epoch":i.epoch,"session":i.session,"generation":i.generation,"local":i.local,"op":op,"base":base,"date":draft.date,"notes":draft.notes,"bbtBits":draft.bbt.to_bits(),"sleepBits":draft.sleep.to_bits()}))
}
fn identity_json(i:&Identity)->Value {json!({"namespace":i.namespace,"effective":i.effective,"instance":i.instance,"epoch":i.epoch,"session":i.session,"generation":i.generation,"local":i.local})}
fn main(){
 let i=Identity{namespace:"synthetic-A".into(),effective:"synthetic-account-A".into(),instance:"db-A-1".into(),epoch:1,session:1,generation:0,local:true};
 assert!(edit(&i,"2024-03-01","",f32::NAN,0.0).is_err());
 assert!(edit(&i,"bad-date","x",0.0,0.0).is_err());
 let mut d=Some(edit(&i,"2024-03-01","",-1.0,-0.0).unwrap()); assert_eq!(confirm(&mut d,&i,"empty",0).unwrap_err(),"Empty");
 let mut d=Some(edit(&i,"2024-03-01","cancel",0.0,0.0).unwrap());d.take();assert_eq!(confirm(&mut d,&i,"cancel",0).unwrap_err(),"NoDraft");
 let mut d=Some(edit(&i,"2024-03-01","old",0.0,0.0).unwrap());let mut next=i.clone();next.epoch=3;assert_eq!(confirm(&mut d,&next,"stale",0).unwrap_err(),"Stale");
 let mut d=Some(edit(&i,"2024-03-01","synthetic note α",-0.0,0.0).unwrap());let plan=confirm(&mut d,&i,"op-1",0).unwrap();assert_eq!(confirm(&mut d,&i,"op-2",0).unwrap_err(),"NoDraft");
 let path=std::env::args().nth(1).unwrap();let corpus:Value=serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();let mut count=0;let mut envelopes=Vec::new();
 for c in corpus["cases"].as_array().unwrap(){
 let mut dates:Vec<String>=(1..=c["gridExpected"].as_array().unwrap().len()).map(|n|format!("{}-{:02}",c["month"].as_str().unwrap(),n)).collect(); dates.push(c["selected"].as_str().unwrap().into());dates.push(c["today"].as_str().unwrap().into());
 let mut expected=c["gridExpected"].as_array().unwrap().clone();expected.push(c["selectedExpected"].clone());expected.push(c["todayExpected"].clone());
 let mut predictions=serde_json::Map::new();for(date,e)in dates.iter().zip(expected.iter()){let s=&c["settings"];let p=cycle_math::calculate_day_prediction(cycle_math::parse_local_date_iso(date).unwrap(),s["lastPeriodStartDate"].as_str().unwrap(),s["averageCycleLength"].as_i64().unwrap() as i32,s["periodDuration"].as_i64().unwrap() as i32,s["lutealPhaseDays"].as_i64().unwrap() as i32);let a=match p{None=>Value::Null,Some(p)=>json!([p.date.to_string(),p.day_of_cycle,p.phase.kotlin_name(),p.is_period,p.is_fertile,p.is_ovulation,p.fertility_confidence,p.pregnancy_chance])};assert_eq!(&a,e);predictions.insert(date.clone(),a);count+=1;}
 let s=&c["settings"];let canonical=json!([s["lastPeriodStartDate"],s["averageCycleLength"],s["periodDuration"],s["lutealPhaseDays"]]).to_string();let digest=format!("{:x}",Sha256::digest(canonical.as_bytes()));
 let env=json!({"identity":identity_json(&i),"request":c["id"],"month":c["month"],"hasCycleAnchor":c["hasCycleAnchor"],"settingsDigest":digest,"gridDates":dates[..dates.len()-2],"selectedDate":dates[dates.len()-2],"todayDate":dates[dates.len()-1],"predictions":predictions});
 assert_eq!(env["predictions"][c["selected"].as_str().unwrap()],c["selectedExpected"]);assert_eq!(env["predictions"][c["today"].as_str().unwrap()],c["todayExpected"]);envelopes.push(env);
 }
 eprintln!("PASS reducer edit/cancel/confirm/finite/negative/one-shot/stale schedules; {count} exact role predictions. Notes/BBT/sleep subset, no full Track/care semantics claimed");
 let out=std::env::args().nth(2).expect("projection output path");std::fs::write(out,serde_json::to_string_pretty(&envelopes).unwrap()).unwrap();
 track::run(&std::env::args().nth(3).unwrap(),&std::env::args().nth(4).unwrap());
 care::run(&std::env::args().nth(5).unwrap());
 settings::run(&std::env::args().nth(6).unwrap());
 println!("{}",plan);
}
