//! Full privacy-filtered Care context assembly, with exact JVM text primitives.
use crate::cycle_math::parse_local_date_iso_full;
pub struct Log {pub date:String,pub flow:Option<String>,pub energy:Option<String>,pub mood:Option<String>,pub symptoms:Option<String>,pub sleep_bits:u32,pub sleep_text:String,pub quality:Option<String>,pub pain:i32}
pub fn compile(question_lower:&str,anchor:Option<&str>,length:i32,logs:&[Log],today:&str)->Option<String> {
 let today=parse_local_date_iso_full(today).epoch_day()?;
 let min=parse_local_date_iso_full("-999999999-01-01").epoch_day()?;
 let has=|keys:&[&str]|keys.iter().any(|k|question_lower.contains(k));
 let cycle=has(&["cycle","period","flow","bleed","timing"]);let energy=has(&["energy","tired","fatigue","exhaust","sleep","rest"]);let mood=has(&["mood","anxious","stress","sad","emotion","irritab"]);let symptoms=has(&["symptom","pain","cramp","headache","migraine","bloat","nausea","pattern","changed","change"]);
 let mut out="User-approved context. Self-reported observations, not diagnosis.\n".to_string();
 if cycle {if let Some(anchor)=anchor {out+=&format!("Cycle anchor: {anchor}; configured cycle length {length} days (calendar estimate only).\n");}}
 let mut recent=Vec::new();
 for log in logs {if let Some(day)=parse_local_date_iso_full(&log.date).epoch_day() {if day<=today {let lower=today-30;if lower<min{return None}if day>=lower {recent.push(log)}}}}
 recent.sort_by(|a,b|b.date.encode_utf16().cmp(a.date.encode_utf16()));
 for log in recent.into_iter().take(8) {
  let mut fields=Vec::new();
  if cycle {if let Some(s)=&log.flow {fields.push(format!("flow={s}"));}}
  if energy {if let Some(s)=&log.energy {fields.push(format!("energy={s}"));}if f32::from_bits(log.sleep_bits)>0.0 {fields.push(format!("sleep={} hours",log.sleep_text));}if let Some(s)=&log.quality {fields.push(format!("sleep quality={s}"));}}
  if mood {if let Some(s)=&log.mood {fields.push(format!("mood={s}"));}}
  if symptoms {if let Some(s)=&log.symptoms {fields.push(format!("symptoms={s}"));}if log.pain>0 {fields.push(format!("pain={}/10",log.pain));}}
  if !fields.is_empty(){out+=&format!("{}: {}\n",log.date,fields.join("; "));}
 }
 if !cycle && !energy && !mood && !symptoms {out+="No relevant saved health observations selected.\n";}
 out+="No free-text notes, account details, sexual activity or intimate-health records included.";
 Some(out)
}
