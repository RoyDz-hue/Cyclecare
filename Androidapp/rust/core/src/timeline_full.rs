//! Whole monthly timeline ownership with full Java dates and platform labels.
use crate::cycle_math::{parse_local_date_iso_full,ParsedLocalDate};
use crate::learning::MonthlyTimelineEntry;
use chrono::Datelike;
use std::collections::{HashMap,HashSet,BTreeMap};
pub struct Tag {pub key:String,pub label:String}
pub struct Log {pub date:String,pub month_title:String,pub energy:Tag,pub bleeding:bool,pub symptoms:Vec<Tag>}
fn month(parsed:ParsedLocalDate)->Option<(i64,u32)> {match parsed {
    ParsedLocalDate::Representable(d)=>Some((d.year() as i64,d.month())),
    ParsedLocalDate::Unrepresentable{year,month,..}=>Some((year,month)),_=>None}}
fn dominant(tags:impl Iterator<Item=Tag>)->Option<String> {
    let mut counts:HashMap<String,(usize,String)>=HashMap::new();let mut order=Vec::new();
    for t in tags {if !counts.contains_key(&t.key) {order.push(t.key.clone());}
        let e=counts.entry(t.key).or_insert((0,t.label));e.0+=1;
    }
    let mut best=None;let mut max=0;
    for key in order {let (n,label)=&counts[&key];if *n>max {max=*n;best=Some(label.clone());}}
    best
}
pub fn generate(logs:&[Log])->Vec<MonthlyTimelineEntry> {
    let mut seen=HashSet::new();let mut dated:Vec<_>=logs.iter().filter_map(|l|parse_local_date_iso_full(&l.date).epoch_day().map(|d|(d,l))).filter(|(d,_)|seen.insert(*d)).collect();dated.sort_by_key(|(d,_)|*d);
    let mut groups:BTreeMap<(i64,u32),Vec<&Log>>=BTreeMap::new();
    for (_,l) in dated {groups.entry(month(parse_local_date_iso_full(&l.date)).unwrap()).or_default().push(l);}
    groups.into_iter().rev().map(|(_,entries)| {
        let energy=dominant(entries.iter().filter(|l|!matches!(l.energy.key.as_str(),""|"NORMAL"|"UNKNOWN")).map(|l|Tag{key:l.energy.key.clone(),label:l.energy.label.clone()}));
        let symptoms=dominant(entries.iter().flat_map(|l| {let mut seen=HashSet::new();l.symptoms.iter().filter(move |s|seen.insert(&s.key)).map(|s|Tag{key:s.key.clone(),label:s.label.clone()})}));
        MonthlyTimelineEntry {month_title:entries[0].month_title.clone(),cycle_length_days:0,period_bleeding_days:entries.iter().filter(|l|l.bleeding).count() as i32,dominant_energy:energy.unwrap_or_else(||"Not recorded".into()),dominant_symptom:symptoms.unwrap_or_else(||"No symptom tags recorded".into()),pattern_noticed:None}
    }).collect()
}
