//! Daily attention prioritization under a strict budget.
//! Port of domain/attention/AttentionEngine.kt (main@31b88f5) - priority
//! order, item ids, categories, and every user-facing string verbatim.
//! Clock seam: caller supplies `today` (the source reads LocalDate.now()).
//!
//! Formatting divergences tracked, not claimed exact: the source uses
//! "%.1f".format, which (a) follows the device default locale's decimal
//! separator (this port always emits '.') and (b) rounds HALF_UP, while Rust's
//! {:.1} rounds half-to-even. The port pre-rounds half-away-from-zero to match
//! HALF_UP for these non-negative averages.

use crate::cycle_math::DayPrediction;
use crate::safety::{SafetyEvaluation, SafetyLevel};
use crate::something_changed::{detect_deviations, ChangeLog, DeviationSeverity};
use chrono::NaiveDate;

/// Log fields this engine reads (verbatim names from CycleDayLog).
#[derive(Debug, Clone)]
pub struct AttentionLog {
    pub date_string: String,
    pub sleep_hours: f32,
    pub sleep_quality: String,
    pub symptoms_csv: String,
    pub pain_intensity: i32,
}

/// Verbatim output shape from data/model/CycleModels.kt TodayAttentionItem.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TodayAttentionItem {
    pub id: &'static str,
    pub category: &'static str,
    pub headline: String,
    pub message: String,
    pub why_this_appeared: String,
    pub care_suggestion: String,
    pub is_urgent: bool,
}

/// Priority order (verbatim from the source): safety > baseline shift >
/// approaching period+sleep > fertile window > phase transition > check-in.
pub fn evaluate_today_priority(
    prediction: Option<&DayPrediction>,
    safety: &SafetyEvaluation,
    average_cycle_length: i32,
    recent_logs: &[AttentionLog],
    today: NaiveDate,
) -> Option<TodayAttentionItem> {
    // Priority 1: Acute deterministic safety concern (NEVER preempted).
    if safety.level == SafetyLevel::Urgent || safety.level == SafetyLevel::SeekProfessionalAdvice {
        return Some(TodayAttentionItem {
            id: "safety_priority",
            category: "Safety",
            headline: safety.title.to_string(),
            message: safety.message.clone(),
            why_this_appeared: "This notice is based on the symptom entry you saved today.".to_string(),
            care_suggestion: safety.action_advice.clone(),
            is_urgent: true,
        });
    }

    // Priority 2: "Something Changed" baseline deviations.
    let change_logs: Vec<ChangeLog> = recent_logs
        .iter()
        .map(|l| ChangeLog {
            date_string: l.date_string.clone(),
            symptoms_csv: l.symptoms_csv.clone(),
            pain_intensity: l.pain_intensity,
        })
        .collect();
    let deviations = detect_deviations(&change_logs, today);
    let strong = deviations.iter().find(|d| d.severity == DeviationSeverity::Strong);
    let notable = deviations
        .iter()
        .find(|d| matches!(d.severity, DeviationSeverity::Significant | DeviationSeverity::Notable));
    if let Some(strong) = strong {
        return Some(TodayAttentionItem {
            id: "baseline_shift_strong",
            category: "Body Rhythm Shift",
            headline: format!("Noticeable change: {}", strong.title),
            message: strong.message.clone(),
            why_this_appeared: "Calculated from comparing your recent 90-day records with your previous historical baseline.".to_string(),
            care_suggestion: "Consider noting any changes in sleep, stress, or nutrition, or discussing with a healthcare professional.".to_string(),
            is_urgent: false,
        });
    }
    if let Some(notable) = notable {
        if prediction.map(|p| p.is_period) != Some(true) {
            return Some(TodayAttentionItem {
                id: "baseline_shift_notable",
                category: "Body Rhythm Shift",
                headline: "A subtle shift in your rhythm".to_string(),
                message: notable.message.clone(),
                why_this_appeared: "Observed across your recent cycle patterns.".to_string(),
                care_suggestion: "Cycle rhythms naturally ebb and flow with lifestyle and seasonal changes.".to_string(),
                is_urgent: false,
            });
        }
    }

    // Do not infer cycle timing from a missing or invalid date anchor.
    let prediction = prediction?;

    // Priority 3: Approaching period & sleep deviation.
    let days_until_period = (average_cycle_length - prediction.day_of_cycle).max(0);
    let mut sorted_logs: Vec<&AttentionLog> = recent_logs.iter().collect();
    sorted_logs.sort_by(|a, b| b.date_string.cmp(&a.date_string)); // stable, desc
    let recent_sleep: Vec<f32> = sorted_logs
        .iter()
        .take(3)
        .filter(|l| {
            l.sleep_hours > 0.0
                && ((l.sleep_hours - 7.5f32).abs() > 0.01f32
                    || !matches!(l.sleep_quality.to_uppercase().as_str(), "" | "GOOD"))
        })
        .map(|l| l.sleep_hours)
        .collect();
    let avg_recent_sleep = if recent_sleep.is_empty() {
        None
    } else {
        Some(recent_sleep.iter().map(|h| *h as f64).sum::<f64>() / recent_sleep.len() as f64)
    };
    if (1..=3).contains(&days_until_period) && avg_recent_sleep.map(|a| a < 6.8).unwrap_or(false) {
        let avg = avg_recent_sleep.unwrap();
        return Some(TodayAttentionItem {
            id: "period_sleep_taper",
            category: "Today's Picture",
            headline: format!("Calendar estimate: period in about {days_until_period} days"),
            // Kotlin "%.1f" rounds HALF_UP; Rust {:.1} is half-to-even.
            // Pre-round half-away-from-zero to match the source display.
            message: format!("You also saved sleep entries averaging {:.1} hours across recent dates.", (avg * 10.0).round() / 10.0),
            why_this_appeared: "Calendar date estimate plus non-default sleep values saved in your logs.".to_string(),
            care_suggestion: "Prioritize a calming evening routine and gentle wind-down tonight.".to_string(),
            is_urgent: false,
        });
    }

    // Priority 4: Approaching fertile window.
    if prediction.is_fertile && !prediction.is_period {
        return Some(TodayAttentionItem {
            id: "fertile_window_notice",
            category: "Cycle Milestone",
            headline: "You may be entering your fertile window".to_string(),
            message: "This calendar estimate places the date within a possible fertile window. It cannot confirm ovulation or prevent pregnancy.".to_string(),
            why_this_appeared: format!("Estimated cycle day {} from your saved date and settings.", prediction.day_of_cycle),
            care_suggestion: "If fertility matters to you, use appropriate clinical guidance rather than relying on a calendar estimate.".to_string(),
            is_urgent: false,
        });
    }

    // Priority 5: Cycle phase transition moment (day 1 of new phase).
    if prediction.day_of_cycle == 1 && prediction.is_period {
        return Some(TodayAttentionItem {
            id: "phase_menstrual_start",
            category: "Cycle Reset",
            headline: "Welcome to Day 1 of your cycle".to_string(),
            message: "Hormones are at their baseline. Your body is taking time to rest and reset.".to_string(),
            why_this_appeared: "Recorded first day of period bleeding.".to_string(),
            care_suggestion: "Focus on warmth, nourishing hydration, and gentle pacing.".to_string(),
            is_urgent: false,
        });
    }

    // Priority 6: Gentle wellbeing check-in if unlogged for 4+ days.
    let last_log_date = recent_logs
        .iter()
        .filter_map(|l| NaiveDate::parse_from_str(&l.date_string, "%Y-%m-%d").ok())
        .max();
    if let Some(last) = last_log_date {
        if (today - last).num_days() >= 4 && !prediction.is_period {
            return Some(TodayAttentionItem {
                id: "wellbeing_checkin",
                category: "Gentle Check-in",
                headline: "How is your body feeling lately?".to_string(),
                message: "Checking in when you have a quiet moment helps keep your body patterns clear.".to_string(),
                why_this_appeared: "No journal entries in the last few days.".to_string(),
                care_suggestion: "Add a quick note or log in Tell CycleCare whenever you feel ready.".to_string(),
                is_urgent: false,
            });
        }
    }
    None
}
