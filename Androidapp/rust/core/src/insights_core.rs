//! Decision-core port of the formatting/visibility rules in
//! ui/screens/InsightsScreen.kt (ref 31b88f5): baseline stat tiles
//! (:134-153), list truncation + toggle labels (:175-234, :335-394),
//! timeline entry text (:354). Compose rendering stays an Android seam.
//!
//! Quirks ported 1:1 and labeled:
//!  - "1 logged bleeding days" - no pluralization on the timeline entry
//!  - baseline average rendered with Kotlin Double.toInt() truncation
//!    (27.9 -> "27d"), unlike pattern_engine's roundToInt elsewhere
//!  - toggle-label builders produce text for any size; the source only
//!    SHOWS the toggle when size > 1 (:204, :231, :391) - visibility is a
//!    separate ported rule.

/// Baseline stat tiles (:134-153). Field order: cycle label, cycle value,
/// median value, variability value, data-points value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BaselineTiles {
    pub cycle_label: String,
    pub cycle_value: String,
    pub median_value: String,
    pub variability_value: String,
    pub data_points_value: String,
}

pub fn baseline_tiles(
    observed_cycle_intervals: i32,
    settings_average_cycle_length: i32,
    baseline_average_cycle_length: f64,
    baseline_median_cycle_length: i32,
    baseline_variability_days: i32,
    baseline_total_logged_days: i32,
) -> BaselineTiles {
    let cycle_label = if observed_cycle_intervals == 0 {
        "Setting".to_string()
    } else if observed_cycle_intervals == 1 {
        "Interval".to_string()
    } else {
        "Average".to_string()
    };
    let cycle_value = if observed_cycle_intervals == 0 {
        format!("{settings_average_cycle_length}d")
    } else {
        // Kotlin Double.toInt(): truncation toward zero.
        format!("{}d", baseline_average_cycle_length as i32)
    };
    let median_value = if observed_cycle_intervals == 0 {
        "\u{2014}".to_string()
    } else {
        format!("{baseline_median_cycle_length}d")
    };
    let variability_value = if observed_cycle_intervals < 2 {
        "Need 2+".to_string()
    } else {
        format!("\u{b1}{baseline_variability_days}d")
    };
    let data_points_value = if baseline_total_logged_days == 1 {
        "1 log".to_string()
    } else {
        format!("{baseline_total_logged_days} logs")
    };
    BaselineTiles {
        cycle_label,
        cycle_value,
        median_value,
        variability_value,
        data_points_value,
    }
}

/// items(...) count (:175, :225, :335): showAll ? size : minOf(size, 1).
pub fn visible_count(show_all: bool, size: usize) -> usize {
    if show_all {
        size
    } else {
        size.min(1)
    }
}

/// Toggle visibility (:204, :231, :391): only when more than one entry.
pub fn show_toggle(size: usize) -> bool {
    size > 1
}

/// :207 observations toggle label.
pub fn observations_toggle_label(show_all: bool, size: usize) -> String {
    if show_all {
        "Show less".to_string()
    } else {
        format!("See all {size} observations")
    }
}

/// :234 patterns toggle label.
pub fn patterns_toggle_label(show_all: bool, size: usize) -> String {
    if show_all {
        "Show fewer patterns".to_string()
    } else {
        format!("See all {size} patterns")
    }
}

/// :394 timeline toggle label.
pub fn timeline_toggle_label(show_all: bool, size: usize) -> String {
    if show_all {
        "Show less history".to_string()
    } else {
        format!("See all {size} months")
    }
}

/// :354 timeline entry detail. No pluralization in the source (1:1 quirk).
pub fn timeline_entry_detail(cycle_length_days: i32, period_bleeding_days: i32) -> String {
    if cycle_length_days > 0 {
        format!("{cycle_length_days}d observed interval")
    } else {
        format!("{period_bleeding_days} logged bleeding days")
    }
}
