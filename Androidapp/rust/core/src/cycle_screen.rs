//! Decision-core port of ui/screens/CycleScreen.kt (ref 31b88f5): the
//! days-left chip (:91), selected-day title fallback (:158) and the day-cell
//! accessibility description builder (:269-276). Compose layout, the
//! span/rounded-corner math (:245-266) and DateTimeFormatter labels (:162)
//! stay Android/locale seams.
//!
//! QUIRK (ported 1:1, labeled): the chip uses the RAW
//! settings.averageCycleLength - no 21..45 clamp - so stored values outside
//! the range produce out-of-model counts (still coerceAtLeast(0)).
//! Kotlin Int semantics: wrapping arithmetic.

/// :91 days-left chip.
pub fn days_left_chip(is_period: bool, average_cycle_length: i32, day_of_cycle: i32) -> i32 {
    if is_period {
        0
    } else {
        average_cycle_length.wrapping_sub(day_of_cycle).max(0)
    }
}

/// :158 selected-day phase title with fallback chain.
pub fn selected_day_title(phase_title: Option<&str>, has_cycle_anchor: bool) -> String {
    match phase_title {
        Some(t) => t.to_string(),
        None => {
            if has_cycle_anchor {
                "No estimate for this date".to_string()
            } else {
                "Add a start date for estimates".to_string()
            }
        }
    }
}

/// :269-276 day-cell content description. The estimate clause is an
/// else-if chain (period wins over ovulation wins over fertile); the
/// has-log and today suffixes append independently.
pub fn day_content_description(
    base: &str,
    is_period: Option<bool>,
    is_ovulation: Option<bool>,
    is_fertile: Option<bool>,
    has_log: bool,
    is_today: bool,
) -> String {
    let mut out = base.to_string();
    if is_period == Some(true) {
        out.push_str(", period estimate");
    } else if is_ovulation == Some(true) {
        out.push_str(", ovulation estimate");
    } else if is_fertile == Some(true) {
        out.push_str(", fertile window estimate");
    }
    if has_log {
        out.push_str(", has a saved log");
    }
    if is_today {
        out.push_str(", today");
    }
    out
}
