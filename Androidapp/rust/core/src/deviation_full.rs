//! Whole SomethingChangedEngine aggregation, preserving duplicate symptom tokens.
use crate::cycle_math::parse_local_date_iso_full;
use std::collections::HashSet;
pub struct Symptom {pub key:String,pub label:String}
pub struct Log {pub date:String,pub pain:i32,pub symptoms:Vec<Symptom>}
pub struct Deviation {pub key:String,pub title:String,pub baseline:f64,pub recent:f64,pub delta:f64,pub severity:&'static str,pub message:String}
fn severity(rank:i32)->&'static str {match rank {2=>"STRONG",1=>"SIGNIFICANT",_=>"NOTABLE"}}
pub fn detect(logs:&[Log],today:&str)->Option<Vec<Deviation>> {
 let today=parse_local_date_iso_full(today).epoch_day()?;
 let mut seen=HashSet::new();let mut dated:Vec<_>=logs.iter().filter_map(|l|parse_local_date_iso_full(&l.date).epoch_day().map(|d|(d,l))).filter(|(d,_)|seen.insert(*d)).collect();dated.sort_by_key(|(d,_)|*d);
 if dated.len()<14{return Some(vec![])}
 let recent:Vec<_>=dated.iter().filter(|(d,_)|today-d<=90).map(|(_,l)|*l).collect();
 let historical:Vec<_>=dated.iter().filter(|(d,_)|today-d>90).map(|(_,l)|*l).collect();
 if recent.is_empty() || historical.is_empty(){return Some(vec![])}
 let mut out:Vec<(i32,Deviation)>=vec![];
 let hp:Vec<_>=historical.iter().map(|l|l.pain).filter(|n|*n>0).collect();let rp:Vec<_>=recent.iter().map(|l|l.pain).filter(|n|*n>0).collect();
 if !hp.is_empty() && !rp.is_empty() {
  let h=hp.iter().map(|n|*n as f64).sum::<f64>()/hp.len() as f64;let r=rp.iter().map(|n|*n as f64).sum::<f64>()/rp.len() as f64;let diff=r-h;
  if diff>=1.5 {let rank=if diff>=3.5{2}else if diff>=2.5{1}else{0};out.push((rank,Deviation{key:"pain_intensity".into(),title:"Pelvic Comfort Shift".into(),baseline:h,recent:r,delta:(diff/h)*100.0,severity:severity(rank),message:"Your logged discomfort levels recently averaged higher than your previous baseline.".into()}));}
 }
 let hs:Vec<_>=historical.iter().flat_map(|l|l.symptoms.iter()).collect();let rs:Vec<_>=recent.iter().flat_map(|l|l.symptoms.iter()).collect();
 let mut seen=HashSet::new();
 for sym in hs.iter().chain(rs.iter()) {if !seen.insert(&sym.key){continue}
  let h=hs.iter().filter(|s|s.key==sym.key).count() as f64/historical.len() as f64;let r=rs.iter().filter(|s|s.key==sym.key).count() as f64/recent.len() as f64;
  if h>0.05 && r>h*1.6 {let rank=if r>=h*2.5{2}else if r>=h*2.0{1}else{0};out.push((rank,Deviation{key:sym.key.clone(),title:format!("{} Frequency Shift",sym.label),baseline:h*100.0,recent:r*100.0,delta:((r-h)/h)*100.0,severity:severity(rank),message:format!("You have recorded {} noticeably more often over your last few cycles.",sym.label)}));}
 }
 out.sort_by(|a,b|b.0.cmp(&a.0));Some(out.into_iter().map(|(_,d)|d).collect())
}
