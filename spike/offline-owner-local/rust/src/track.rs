use crate::{sync_codec as c,cycle_math};
use serde_json::{Value,json};
fn anchor(flow:&str,date:&str,before:&str)->Option<String>{
 if !["LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY"].contains(&flow){return None}
 let day=cycle_math::parse_local_date_iso(date)?;
 let prior=cycle_math::parse_local_date_iso(before);
 if prior.is_none()||day>prior.unwrap().checked_add_signed(chrono::Duration::days(15)).unwrap(){Some(date.into())}else{None}
}
fn log(v:&Value)->c::CycleDayLog{
 let p:Value=serde_json::from_str(v["payload"].as_str().unwrap()).unwrap();
 let strval=|k:&str|p[k].as_str().unwrap().to_string();
 c::CycleDayLog{date:strval("date"),flow:strval("flow"),energy:strval("energy"),mood:strval("mood"),symptoms:strval("symptoms"),vaginal_health:strval("vaginal_health"),urinary_health:strval("urinary_health"),cervical_mucus:strval("cervical_mucus"),ovulation_test:strval("ovulation_test"),bbt_celsius:p["bbt_celsius"].as_f64().unwrap() as f32,sleep_hours:p["sleep_hours"].as_f64().unwrap() as f32,sleep_quality:strval("sleep_quality"),water_glasses:p["water_glasses"].as_i64().unwrap() as i32,notes:strval("notes"),pain_intensity:p["pain_intensity"].as_i64().unwrap() as i32}
}
pub fn run(path:&str,out:&str){
 let corpus:Value=serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();let mut plans=Vec::new();let mut count=0;
 for v in corpus["cases"].as_array().unwrap(){let l=log(v);let payload=c::log_payload(&l);assert_eq!(payload,v["payload"].as_str().unwrap());assert_eq!(c::hash(Some(&payload)),v["hash"].as_str().unwrap());
 let next=anchor(&l.flow,&l.date,v["anchorBefore"].as_str().unwrap());assert_eq!(json!(next),v["anchorAfter"]);
 let mut settings=c::CycleSettings::default();settings.last_period_start_date=v["anchorBefore"].as_str().unwrap().into();settings.selected_goals_csv="cycle".into();
 let before_payload=c::settings_payload(&settings);if let Some(ref n)=next{settings.last_period_start_date=n.clone()}
 let after=c::settings_payload(&settings);if !v["settingsPayloadAfter"].is_null(){assert_eq!(after,v["settingsPayloadAfter"].as_str().unwrap())}
 plans.push(json!({"kind":"UPSERT","op":format!("track-{count}"),"date":l.date,"payload":payload,"payloadHash":c::hash(Some(&payload)),"bbtBits":l.bbt_celsius.to_bits(),"sleepBits":l.sleep_hours.to_bits(),"anchorBefore":v["anchorBefore"],"anchorAfter":next,"settingsBefore":before_payload,"settingsAfter":after,"settingsHash":c::hash(Some(&after))}));count+=1;
 }
 assert_eq!(c::hash(None),corpus["deleteHash"].as_str().unwrap());
 plans.push(json!({"kind":"DELETE","op":"delete-1","date":"2024-03-16","payload":null,"payloadHash":c::hash(None),"anchorAfter":null}));
 for p in &mut plans {p["identity"]=json!({"namespace":"synthetic-A","effective":"synthetic-account-A","instance":"db-A-1","epoch":1,"session":1,"generation":0,"local":true});let anchor_hash=if p["anchorAfter"].is_null(){String::new()}else{p["settingsHash"].as_str().unwrap().to_string()};let parts=[p["kind"].as_str().unwrap(),p["date"].as_str().unwrap(),p["payloadHash"].as_str().unwrap(),&anchor_hash];let canonical=c::json_value_to_string(&c::JsonValue::Array(parts.iter().map(|s|c::JsonValue::Str(s.to_string())).collect()));p["intentDigest"]=json!(c::sha256_hex(&canonical));}
 std::fs::write(out,serde_json::to_string_pretty(&plans).unwrap()).unwrap();eprintln!("PASS33 exact current Kotlin/AOSP full-field log payload/hash and conditional-anchor cases; settings payloads28; delete null hash");
}
