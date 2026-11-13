//! Current live non-date onboarding reducer. Ordered sets/maps mirror Kotlin +/-.
use serde::{Deserialize,Serialize};
#[derive(Clone,Deserialize,Serialize)] #[serde(deny_unknown_fields,rename_all="camelCase")]
pub struct Answer {pub id:String,pub values:Vec<String>}
#[derive(Clone,Deserialize,Serialize)] #[serde(deny_unknown_fields,rename_all="camelCase")]
pub struct State {
 pub step:i32,pub total:i32,pub goals:Vec<String>,pub answers:Vec<Answer>,pub symptoms:Vec<String>,pub energy:String,pub sleep:String,pub cycle_option:String,pub cycle_days:i32,
 pub not_sure:bool,pub date_picker:bool,pub end_picker:bool,pub remind:Option<bool>,pub age:bool,pub terms:bool,pub cloud:bool,pub terms_dialog:bool,pub privacy_dialog:bool,pub deep:bool,pub end_confirmed:bool,pub date_dialog:Option<String>
}
#[derive(Deserialize)] #[serde(deny_unknown_fields)]
pub struct Event {pub action:String,pub text:String,pub other:String,pub flag:bool,pub days:i32}
fn toggle(values:&mut Vec<String>,value:String){if let Some(i)=values.iter().position(|v|v==&value){values.remove(i);}else{values.push(value);}}
fn unique(values:&[String])->bool {let mut seen=std::collections::HashSet::new();values.iter().all(|v|seen.insert(v))}
pub fn reduce(mut s:State,e:Event)->Option<State>{
 if !unique(&s.goals)||!unique(&s.symptoms)||!unique(&s.answers.iter().map(|a|a.id.clone()).collect::<Vec<_>>())||s.answers.iter().any(|a|!unique(&a.values)){return None}
 match e.action.as_str(){
 "NEXT"=>if s.step<s.total{s.step=s.step.wrapping_add(1)},
 "PREVIOUS"=>if s.step>1{s.step=s.step.wrapping_sub(1)},
 "GOAL"=>toggle(&mut s.goals,e.text),
 "ANSWER"=>{let i=match s.answers.iter().position(|a|a.id==e.text){Some(i)=>i,None=>{s.answers.push(Answer{id:e.text,values:vec![]});s.answers.len()-1}};if e.flag{toggle(&mut s.answers[i].values,e.other)}else{s.answers[i].values=vec![e.other]}},
 "NOT_SURE"=>s.not_sure=e.flag,"DATE_PICKER"=>s.date_picker=e.flag,"END_PICKER"=>s.end_picker=e.flag,
 "CYCLE"=>{s.cycle_option=e.text;s.cycle_days=e.days},"REMIND"=>s.remind=Some(e.flag),"AGE"=>s.age=e.flag,"TERMS"=>s.terms=e.flag,"CLOUD"=>s.cloud=e.flag,
 "TERMS_DIALOG"=>s.terms_dialog=e.flag,"PRIVACY_DIALOG"=>s.privacy_dialog=e.flag,
 "SYMPTOM"=>{toggle(&mut s.symptoms,e.text);s.deep=true},"ENERGY"=>{s.energy=e.text;s.deep=true},"SLEEP"=>{s.sleep=e.text;s.deep=true},"DEEP"=>s.deep=e.flag,
 "CONFIRM_END"=>{s.end_confirmed=true;s.date_dialog=None},"EDIT_END"=>{s.date_dialog=None;s.end_picker=true},_=>return None
 };Some(s)
}
