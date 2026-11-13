//! Live date-entry screen state. JVM supplies lazily evaluated clock/date primitives.
use serde::{Deserialize,Serialize};
#[derive(Clone,Deserialize,Serialize)] #[serde(deny_unknown_fields,rename_all="camelCase")]
pub struct State {pub exact:Option<i64>,pub end:Option<i64>,pub chip:Option<String>,pub duration:i32,pub option:String,pub ongoing:bool,pub date_picker:bool,pub end_picker:bool,pub confirmed:bool,pub dialog:Option<String>}
#[derive(Deserialize)] #[serde(deny_unknown_fields,rename_all="camelCase")]
pub struct Input {pub state:State,pub action:String,pub date:Option<i64>,pub text:String,pub days:i32,pub ongoing:bool,pub effective_start:Option<i64>,pub calculated_end:Option<i64>,pub today:Option<i64>}
fn valid(v:i64)->bool{(-365243219162..=365241780471).contains(&v)}
pub fn reduce(i:Input)->Option<State>{
 let mut s=i.state;
 if [s.exact,s.end,i.date,i.effective_start,i.calculated_end,i.today].iter().any(|d|d.map_or(false,|v|!valid(v))){return None}
 match i.action.as_str(){
 "EXACT_START"=>{let date=i.date?;let end=s.end.map(|e|if e>=date{e}else{date});
 if let Some(end)=end {let decision=crate::period_episode_full::decide(Some(date),Some(end),i.today?,false,s.duration);s.duration=decision.typical;s.dialog=if decision.confirm{decision.warning}else{None};s.confirmed=!decision.confirm}else{s.dialog=None}
 s.exact=Some(date);s.end=end;s.chip=None;s.date_picker=false},
 "APPROXIMATE"=>{s.chip=Some(i.text);s.exact=None},
 "EXACT_END"=>{let date=i.date?;let start=i.effective_start?;
 if date<start{s.end=Some(start);s.ongoing=false;s.end_picker=false;s.dialog=None;s.confirmed=true}
 else {let d=crate::period_episode_full::decide(Some(start),Some(date),i.today?,false,s.duration);s.end=Some(date);s.duration=d.typical;s.option=format!("{} days",date-start+1);s.ongoing=false;s.end_picker=false;s.dialog=if d.confirm{d.warning}else{None};s.confirmed=!d.confirm}},
 "DURATION"=>{s.option=i.text;s.duration=i.days;s.ongoing=i.ongoing;s.end=if i.ongoing{None}else{Some(i.calculated_end?)};s.confirmed=false;s.dialog=None},_=>return None
 };Some(s)
}
