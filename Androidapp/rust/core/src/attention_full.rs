//! Nonsafety attention evidence and full output. Immediate safety remains Kotlin.
use crate::attention::TodayAttentionItem;
pub struct SleepLog {pub date:String,pub hours:f32,pub quality:String}
pub fn prepare(average:i32,day:i32,logs:&[SleepLog])->Option<(i32,Option<f64>)> {
 if logs.iter().any(|l|!l.hours.is_finite()){return None}
 let until=average.wrapping_sub(day).max(0);
 let mut sorted:Vec<_>=logs.iter().collect();sorted.sort_by(|a,b|b.date.encode_utf16().cmp(a.date.encode_utf16()));
 let values:Vec<_>=sorted.into_iter().take(3).filter(|l|l.hours>0.0 && ((l.hours-7.5f32).abs()>0.01f32 || !matches!(l.quality.as_str(),""|"GOOD"))).map(|l|l.hours as f64).collect();
 let avg=if values.is_empty(){None}else{Some(values.iter().sum::<f64>()/values.len() as f64)};
 Some((until,avg))
}
pub fn render(branch:&str,title:&str,message:&str,days_until_period:i32,day:i32,sleep_text:&str)->Option<TodayAttentionItem> {
 Some(match branch {
"baseline_shift_strong" => TodayAttentionItem {
            id: "baseline_shift_strong",
            category: "Body Rhythm Shift",
            headline: format!("Noticeable change: {}", title),
            message: message.to_string(),
            why_this_appeared: "Calculated from comparing your recent 90-day records with your previous historical baseline.".to_string(),
            care_suggestion: "Consider noting any changes in sleep, stress, or nutrition, or discussing with a healthcare professional.".to_string(),
            is_urgent: false,
},
"baseline_shift_notable" => TodayAttentionItem {
                id: "baseline_shift_notable",
                category: "Body Rhythm Shift",
                headline: "A subtle shift in your rhythm".to_string(),
                message: message.to_string(),
                why_this_appeared: "Observed across your recent cycle patterns.".to_string(),
                care_suggestion: "Cycle rhythms naturally ebb and flow with lifestyle and seasonal changes.".to_string(),
                is_urgent: false,
},
"period_sleep_taper" => TodayAttentionItem {
            id: "period_sleep_taper",
            category: "Today's Picture",
            headline: format!("Calendar estimate: period in about {days_until_period} days"),
            message: format!("You also saved sleep entries averaging {sleep_text} hours across recent dates."),
            why_this_appeared: "Calendar date estimate plus non-default sleep values saved in your logs.".to_string(),
            care_suggestion: "Prioritize a calming evening routine and gentle wind-down tonight.".to_string(),
            is_urgent: false,
},
"fertile_window_notice" => TodayAttentionItem {
            id: "fertile_window_notice",
            category: "Cycle Milestone",
            headline: "You may be entering your fertile window".to_string(),
            message: "This calendar estimate places the date within a possible fertile window. It cannot confirm ovulation or prevent pregnancy.".to_string(),
            why_this_appeared: format!("Estimated cycle day {} from your saved date and settings.", day),
            care_suggestion: "If fertility matters to you, use appropriate clinical guidance rather than relying on a calendar estimate.".to_string(),
            is_urgent: false,
},
"phase_menstrual_start" => TodayAttentionItem {
            id: "phase_menstrual_start",
            category: "Cycle Reset",
            headline: "Welcome to Day 1 of your cycle".to_string(),
            message: "Hormones are at their baseline. Your body is taking time to rest and reset.".to_string(),
            why_this_appeared: "Recorded first day of period bleeding.".to_string(),
            care_suggestion: "Focus on warmth, nourishing hydration, and gentle pacing.".to_string(),
            is_urgent: false,
},
"wellbeing_checkin" => TodayAttentionItem {
                id: "wellbeing_checkin",
                category: "Gentle Check-in",
                headline: "How is your body feeling lately?".to_string(),
                message: "Checking in when you have a quiet moment helps keep your body patterns clear.".to_string(),
                why_this_appeared: "No journal entries in the last few days.".to_string(),
                care_suggestion: "Add a quick note or log in Tell CycleCare whenever you feel ready.".to_string(),
                is_urgent: false,
},
_=>return None,
 })
}
