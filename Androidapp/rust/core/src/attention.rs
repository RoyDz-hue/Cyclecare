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


// ---------------------------------------------------------------------------
// Bounded NON-SAFETY priority selector (two-pass). SELECTION ONLY: all
// evidence is prepared and all user-facing copy is rendered by the Kotlin
// caller (AttentionEngine). The wellbeing check-in (source P6) is decided
// ONLY on the second pass, after the caller resolves its late current-date
// evidence at the original logical stage; on the first pass the selector
// reports NeedsCheckin when no earlier branch wins. No dates are parsed and
// no clock is read here: every value arrives from the caller. The source
// safety branch (P1) stays an immediate Kotlin return in the product path;
// the SafetyPriority tag exists for direct selector tests only.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NonsafetyDecision {
    SafetyPriority,
    BaselineShiftStrong { deviation_index: usize },
    BaselineShiftNotable { deviation_index: usize },
    /// Source null-prediction stop after the deviation branches, or a
    /// resolved check-in whose condition did not hold.
    None,
    PeriodSleepTaper,
    FertileWindowNotice,
    PhaseMenstrualStart,
    /// First pass only: no earlier branch won; the caller must resolve the
    /// late check-in evidence in Kotlin and re-query.
    NeedsCheckin,
    WellbeingCheckin,
}

#[derive(Debug, Clone)]
pub struct NonsafetyEvidence<'a> {
    /// The product wrapper never routes safety natively (immediate Kotlin
    /// return); direct selector tests may exercise the tag.
    pub safety_urgent_or_seek: bool,
    /// Ordered EXACTLY as the Kotlin SomethingChangedEngine returned them.
    pub deviation_severities: &'a [DeviationSeverity],
    pub has_prediction: bool,
    pub day_of_cycle: i32,
    pub is_period: bool,
    pub is_fertile: bool,
    /// The Kotlin caller's wrapping (averageCycleLength - dayOfCycle)
    /// coerceAtLeast(0) result; no subtraction happens here.
    pub days_until_period: i32,
    /// The Kotlin caller's exact take3-filter Float->Double sequential
    /// average; None when the filtered set is empty or there is no
    /// prediction.
    pub sleep_average: Option<f64>,
    /// First pass: None (unresolved). Second pass: Some(None) when the
    /// source's lastLogDate == null branch applies, Some(Some(days)) with
    /// the caller's exact ChronoUnit.DAYS.between result.
    pub checkin_days_since_last_log: Option<Option<i64>>,
}

/// Priority order (verbatim from the source): safety (tag only) > strong
/// deviation > notable/significant deviation (suppressed on period) >
/// null-prediction stop > period+sleep (1..=3 days, avg < 6.8) > fertile
/// window (not period) > day-1 phase transition > check-in (>= 4 days, not
/// period, second pass only).
pub fn select_nonsafety_priority(ev: &NonsafetyEvidence) -> NonsafetyDecision {
    if ev.safety_urgent_or_seek {
        return NonsafetyDecision::SafetyPriority;
    }
    if let Some(i) = ev.deviation_severities.iter().position(|s| *s == DeviationSeverity::Strong) {
        return NonsafetyDecision::BaselineShiftStrong { deviation_index: i };
    }
    // Source: notableDev != null && prediction?.isPeriod != true - an absent
    // prediction passes is_period == false.
    if !ev.is_period {
        if let Some(i) = ev
            .deviation_severities
            .iter()
            .position(|s| matches!(s, DeviationSeverity::Significant | DeviationSeverity::Notable))
        {
            return NonsafetyDecision::BaselineShiftNotable { deviation_index: i };
        }
    }
    // Source: do not infer cycle timing from a missing or invalid anchor.
    if !ev.has_prediction {
        return NonsafetyDecision::None;
    }
    if (1..=3).contains(&ev.days_until_period) && ev.sleep_average.map(|a| a < 6.8).unwrap_or(false) {
        return NonsafetyDecision::PeriodSleepTaper;
    }
    if ev.is_fertile && !ev.is_period {
        return NonsafetyDecision::FertileWindowNotice;
    }
    if ev.day_of_cycle == 1 && ev.is_period {
        return NonsafetyDecision::PhaseMenstrualStart;
    }
    match ev.checkin_days_since_last_log {
        None => NonsafetyDecision::NeedsCheckin,
        Some(None) => NonsafetyDecision::None,
        Some(Some(days)) => {
            if days >= 4 && !ev.is_period {
                NonsafetyDecision::WellbeingCheckin
            } else {
                NonsafetyDecision::None
            }
        }
    }
}

#[cfg(test)]
mod nonsafety_selector_tests {
    use super::*;

    fn base<'a>(sevs: &'a [DeviationSeverity]) -> NonsafetyEvidence<'a> {
        NonsafetyEvidence {
            safety_urgent_or_seek: false,
            deviation_severities: sevs,
            has_prediction: true,
            day_of_cycle: 10,
            is_period: false,
            is_fertile: false,
            days_until_period: 18,
            sleep_average: None,
            checkin_days_since_last_log: None,
        }
    }

    #[test]
    fn safety_tag_first() {
        let ev = NonsafetyEvidence { safety_urgent_or_seek: true, ..base(&[DeviationSeverity::Strong]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::SafetyPriority);
    }

    #[test]
    fn strong_wins_and_keeps_original_index() {
        let sevs = [DeviationSeverity::Notable, DeviationSeverity::Strong];
        assert_eq!(select_nonsafety_priority(&base(&sevs)), NonsafetyDecision::BaselineShiftStrong { deviation_index: 1 });
    }

    #[test]
    fn notable_first_significant_or_notable() {
        let sevs = [DeviationSeverity::Notable, DeviationSeverity::Significant];
        assert_eq!(select_nonsafety_priority(&base(&sevs)), NonsafetyDecision::BaselineShiftNotable { deviation_index: 0 });
    }

    #[test]
    fn notable_suppressed_on_period() {
        let ev = NonsafetyEvidence { is_period: true, ..base(&[DeviationSeverity::Notable]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::NeedsCheckin);
    }

    #[test]
    fn null_prediction_stops_after_deviations() {
        let ev = NonsafetyEvidence { has_prediction: false, ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::None);
    }

    #[test]
    fn sleep_taper_boundaries() {
        for (days, avg, expect) in [(1, Some(6.79), true), (3, Some(6.0), true), (0, Some(6.0), false), (4, Some(6.0), false), (2, Some(6.8), false), (2, None, false)] {
            let ev = NonsafetyEvidence { days_until_period: days, sleep_average: avg, ..base(&[]) };
            let got = select_nonsafety_priority(&ev) == NonsafetyDecision::PeriodSleepTaper;
            assert_eq!(got, expect, "days={days} avg={avg:?}");
        }
    }

    #[test]
    fn fertile_requires_not_period() {
        let ev = NonsafetyEvidence { is_fertile: true, ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::FertileWindowNotice);
        let ev = NonsafetyEvidence { is_fertile: true, is_period: true, ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::NeedsCheckin);
    }

    #[test]
    fn day1_requires_period() {
        let ev = NonsafetyEvidence { day_of_cycle: 1, is_period: true, ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::PhaseMenstrualStart);
        let ev = NonsafetyEvidence { day_of_cycle: 1, is_period: false, ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&ev), NonsafetyDecision::NeedsCheckin);
    }

    #[test]
    fn checkin_two_pass() {
        let unresolved = base(&[]);
        assert_eq!(select_nonsafety_priority(&unresolved), NonsafetyDecision::NeedsCheckin);
        let no_date = NonsafetyEvidence { checkin_days_since_last_log: Some(None), ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&no_date), NonsafetyDecision::None);
        let three = NonsafetyEvidence { checkin_days_since_last_log: Some(Some(3)), ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&three), NonsafetyDecision::None);
        let four = NonsafetyEvidence { checkin_days_since_last_log: Some(Some(4)), ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&four), NonsafetyDecision::WellbeingCheckin);
        let four_period = NonsafetyEvidence { is_period: true, checkin_days_since_last_log: Some(Some(4)), ..base(&[]) };
        assert_eq!(select_nonsafety_priority(&four_period), NonsafetyDecision::None);
    }
}
