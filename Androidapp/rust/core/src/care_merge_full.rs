//! Affected-field merge patch. JVM retains the original untouched entity fields.
use std::collections::HashSet;
pub struct Patch {pub flow:String,pub energy:String,pub mood:String,pub symptoms:String,pub pain:i32}
pub fn merge(flow:String,energy:String,mood:String,pain:i32,new_flow:Option<String>,new_energy:Option<String>,new_mood:Option<String>,new_pain:i32,tokens:Vec<String>)->Patch {
 let mut seen=HashSet::new();let symptoms=tokens.into_iter().filter(|s|seen.insert(s.clone())).collect::<Vec<_>>().join(",");
 Patch{flow:new_flow.unwrap_or(flow),energy:new_energy.unwrap_or(energy),mood:new_mood.unwrap_or(mood),symptoms,pain:if new_pain>0 {new_pain.clamp(0,10)}else{pain}}
}
