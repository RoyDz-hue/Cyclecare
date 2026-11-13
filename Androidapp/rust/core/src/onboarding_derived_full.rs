//! Lazy derived onboarding decisions. Missing evidence requests one specific primitive.
use serde_json::{Value,json};
fn get<'a>(e:&'a serde_json::Map<String,Value>,k:&str)->Result<&'a Value,Value>{e.get(k).ok_or_else(||json!({"need":k}))}
fn boolean(e:&serde_json::Map<String,Value>,k:&str)->Result<bool,Value>{get(e,k)?.as_bool().ok_or(json!({"invalid":true}))}
fn int(e:&serde_json::Map<String,Value>,k:&str)->Result<i32,Value>{let n=get(e,k)?.as_i64().ok_or(json!({"invalid":true}))?;i32::try_from(n).map_err(|_|json!({"invalid":true}))}
fn eval(op:&str,e:&serde_json::Map<String,Value>)->Result<Value,Value>{
 Ok(match op {
 "privacy"=>json!(boolean(e,"age")?&&boolean(e,"terms")?),
 "answered"=>json!(boolean(e,"exact")?||boolean(e,"chipPresent")?),
 "estimate"=>json!(boolean(e,"exact")?||(boolean(e,"chipPresent")?&&!boolean(e,"unknown")?)),
 "choice"=>if boolean(e,"exact")?{json!("EXACT")}else if !boolean(e,"estimate")?{json!("UNKNOWN")}else{let c=get(e,"chip")?.as_str().ok_or(json!({"invalid":true}))?;json!(format!("APPROXIMATE:{c}"))},
 "days"=>{let span=get(e,"span")?.as_i64().ok_or(json!({"invalid":true}))?;json!((span as i32).max(0))},
 "day"=>if boolean(e,"estimate")?{let days=int(e,"days")?;let cycle=int(e,"cycle")?;if cycle==0{return Err(json!({"throw":"arithmetic"}))}json!(days.wrapping_rem(cycle).wrapping_add(1))}else{json!(0)},
 "active"=>json!(boolean(e,"estimate")?&&(boolean(e,"ongoing")?||int(e,"day")?<=int(e,"duration")?)),
 "phase"=>{if !boolean(e,"estimate")?{json!("No estimate yet")}else if boolean(e,"active")?{json!("Menstrual Phase")}else{let first=int(e,"day1")?;let cycle=int(e,"cycle")?;let lower=cycle.wrapping_sub(14).wrapping_sub(4);if first<lower{json!("Follicular Phase")}else{let second=int(e,"day2")?;let upper=cycle.wrapping_sub(14).wrapping_add(1);if second>=lower&&second<=upper{json!("Estimated fertile window")}else{json!("Luteal Phase")}}}},
 "offset"=>{let chip=get(e,"chip")?;let chip=if chip.is_null(){None}else{Some(chip.as_str().ok_or(json!({"invalid":true}))?)};json!(match chip{Some("Today")=>0,Some("A few days ago")=>4,Some("About a week ago")=>7,Some("About two weeks ago")=>14,Some("It varies / I don’t know")|Some("I don’t know")=>0,_=>11})},
 "endOffset"=>json!(int(e,"duration")?.wrapping_sub(1).max(0)),
 "any"=>{let flags=get(e,"flags")?.as_array().ok_or(json!({"invalid":true}))?;let mut result=false;for flag in flags{result|=flag.as_bool().ok_or(json!({"invalid":true}))?}json!(result)},
 "confirmed"=>json!(!boolean(e,"ongoing")?&&boolean(e,"confirmed")?&&boolean(e,"exact")?&&boolean(e,"end")?&&boolean(e,"ordered")?),
 _=>return Err(json!({"invalid":true}))
 })
}
pub fn query(op:&str,evidence:&Value)->Option<Value>{let e=evidence.as_object()?;
 let allowed:&[&str]=match op {"privacy"=>&["age","terms"],"answered"=>&["exact","chipPresent"],"estimate"=>&["exact","chipPresent","unknown"],"choice"=>&["exact","estimate","chip"],"days"=>&["span"],"day"=>&["estimate","days","cycle"],"active"=>&["estimate","ongoing","day","duration"],"phase"=>&["estimate","active","day1","cycle","day2"],"offset"=>&["chip"],"endOffset"=>&["duration"],"any"=>&["flags"],"confirmed"=>&["ongoing","confirmed","exact","end","ordered"],_=>return None};
 for(k,v)in e {if !allowed.contains(&k.as_str()){return None}let valid=match k.as_str(){"chip"=>v.is_string()||(op=="offset"&&v.is_null()),"span"=>v.as_i64().is_some(),"days"|"cycle"|"day"|"duration"|"day1"|"day2"=>v.as_i64().map_or(false,|n|i32::try_from(n).is_ok()),"flags"=>v.as_array().map_or(false,|a|a.iter().all(|v|v.is_boolean())),_=>v.is_boolean()};if !valid{return None}}
 match eval(op,e){Ok(value)=>Some(json!({"version":1,"value":value})),Err(next)=>{if let Some(need)=next.get("need"){Some(json!({"version":1,"need":need}))}else if next.get("throw").is_some(){Some(json!({"version":1,"throw":"arithmetic"}))}else{None}}}}
