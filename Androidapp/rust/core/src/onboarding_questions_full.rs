//! Live adaptive onboarding question output and ordered question-state publication.
use serde::{Deserialize,Serialize};
#[derive(Clone,Deserialize,Serialize)] #[serde(deny_unknown_fields,rename_all="camelCase")]
pub struct Question {pub id:String,pub prompt:String,pub options:Vec<String>,pub multi_select:bool}
#[derive(Clone,Deserialize,Serialize)] #[serde(deny_unknown_fields)]
pub struct Entry {pub step:i32,pub questions:Vec<Question>}
fn question(id:&str,prompt:&str,options:&[&str],multi_select:bool)->Question {Question{id:id.into(),prompt:prompt.into(),options:options.iter().map(|s|s.to_string()).collect(),multi_select}}
pub fn bank(step:i32,goals:&str,earlier:&str)->Vec<Question> {
 let cycle=goals.contains("cycle")||goals.contains("period");let symptoms=goals.contains("symptom")||goals.contains("changes in my body");let fertility=goals.contains("fertile")||goals.contains("fertility");let tuning=goals.contains("in tune")||goals.contains("exploring");
 let mut out=Vec::new();match step {
 4 => {
 if cycle {out.push(question("s4_rhythm_focus","What feels most unpredictable about your cycle right now?",&["Timing", "Flow", "PMS", "Energy", "Nothing really"],true));}
 if symptoms {out.push(question("s4_symptom_focus","Which changes do you notice most between phases?",&["Mood", "Skin", "Sleep", "Appetite", "Cramps", "Energy"],true));}
 if fertility {out.push(question("s4_fertility_focus","What would help you most with fertility awareness?",&["Fertile window timing", "Ovulation signs", "Cycle regularity", "Just learning"],true));}
 if out.is_empty()||tuning {out.push(question("s4_tuning_focus","What would you like CycleCare to notice for you?",&["Energy shifts", "Mood patterns", "Routine changes", "Surprises me"],true));}
 },
 5 => {
 if true {out.push(question("s5_gentle_checkins","How would you like check-ins to feel?",&["Quiet and minimal", "Warm and encouraging", "Only when something changes"],false));}
 if earlier.contains("sleep")||earlier.contains("energy") {out.push(question("s5_rest_focus","Should CycleCare pay extra attention to rest and energy patterns?",&["Yes, rest matters", "Somewhat", "Not specifically"],false));}
 },
 6 => {
 if true {out.push(question("s6_support_style","When something changes in your pattern, what should CycleCare do first?",&["Explain gently what it might mean", "Suggest simple self-care", "Just note it quietly"],false));}
 if true {out.push(question("s6_depth","How much detail do you want in insights?",&["Short and simple", "Balanced", "Deep explanations"],false));}
 },
 _=>{} };out
}
pub fn publish(mut entries:Vec<Entry>,step:i32,incoming:Option<Vec<Question>>,action:&str)->Option<(Vec<Entry>,bool)> {
 let mut seen=std::collections::HashSet::new();if !entries.iter().all(|e|seen.insert(e.step)){return None}
 let replacement=match action {"LOCAL"=>Some(incoming?),"START"=>return Some((entries,true)),"GENERATED"=>incoming.filter(|v|!v.is_empty()),_=>return None};
 if let Some(questions)=replacement {if let Some(e)=entries.iter_mut().find(|e|e.step==step){e.questions=questions}else{entries.push(Entry{step,questions})}}
 Some((entries,false))
}
