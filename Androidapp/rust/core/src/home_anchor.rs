//! Port of the CycleCareViewModel.updateCalculations cycle-anchor slice
//! (repo main@31b88f5, ccvm L469-501) plus HomeScreen's snapshot-card
//! inputs: hasCycleAnchor, cycleStartIsApproximate, daysUntilNextPeriod,
//! nextPeriodDate. Semantics locked against the JVM (kotlinc 2.1.0/JRE 21
//! probe, 15 classify rows + 5 stepping rows in tests/home_anchor_corpus.rs):
//! - trim() is Kotlin no-arg trim = kotlin_is_whitespace-based (NBSP/2007/202F
//!   ARE trimmed - probe rows c13/c14), NOT Java <= ' '.
//! - "don't know" matches with BOTH apostrophes (' and U+2019).
//! - next period start is STRICTLY after today: when last start == today it
//!   steps a full cycle (probe row n1); when it is already after today it
//!   stays (n2). Cycle length clamps to 21..=45 before stepping (n4).

use crate::cycle_math::{parse_local_date_iso_full, ParsedLocalDate};
use crate::kotlin_text::kotlin_is_whitespace;
use chrono::NaiveDate;

#[derive(Debug, Clone, PartialEq)]
pub struct CycleAnchorState {
    pub has_cycle_anchor: bool,
    pub cycle_start_is_approximate: bool,
    pub days_until_next_period: i32,
    pub next_period_date: Option<NaiveDate>,
}

fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(kotlin_is_whitespace)
}

/// (unknown_date_choice, unknown_cycle_length_choice, approximate_choice).
pub fn classify_choices(
    date_choice_raw: Option<&str>,
    cycle_length_choice_raw: Option<&str>,
) -> (bool, bool, bool) {
    let date_choice = date_choice_raw.map(kotlin_trim).unwrap_or("").to_lowercase();
    let unknown_date = date_choice.is_empty()
        || date_choice == "unknown"
        || date_choice.contains("don't know")
        || date_choice.contains("don\u{2019}t know")
        || date_choice == "it varies";
    let clc = cycle_length_choice_raw.map(kotlin_trim).unwrap_or("").to_lowercase();
    let unknown_cl = clc.is_empty()
        || clc == "unknown"
        || clc.contains("don't know")
        || clc.contains("don\u{2019}t know")
        || clc.contains("changes");
    let approximate = date_choice.starts_with("approximate:")
        || matches!(
            date_choice.as_str(),
            "today" | "a few days ago" | "about a week ago" | "about two weeks ago"
        );
    (unknown_date, unknown_cl, approximate)
}

/// Next period start strictly after `today`, stepping by the clamped cycle
/// length. Kotlin: while (!nextStart.isAfter(today)) nextStart += cycleLength.
/// O(1) equivalent of that loop: the loop lands on last_start + k*cycle for
/// the smallest k >= 0 with k*cycle > (today - last_start), i.e.
/// k = floor(diff / cycle) + 1 (k = 0 when the anchor is already after
/// today). Addition is CHECKED: a step past the native date range is None
/// (caller treats it as unsupported and falls back), never a panic; Kotlin
/// would still be looping fine in its wider range there, so faking a value
/// or catching a panic would both misreport the domain.
pub fn next_period_after(last_start: NaiveDate, today: NaiveDate, average_cycle_length: i32) -> Option<NaiveDate> {
    let cycle_length = average_cycle_length.clamp(21, 45) as i64;
    let diff = (today - last_start).num_days();
    let k = if diff < 0 { 0 } else { diff / cycle_length + 1 };
    last_start.checked_add_signed(chrono::Duration::days(k * cycle_length))
}

/// Returns None when the anchor is Java-valid but outside the native date
/// range, or when the checked next-period step overflows it (caller falls
/// back to Kotlin). On Some, the second element is the parsed anchor date
/// when it is Representable - callers must compute predictions only then;
/// a Java-invalid anchor keeps has_cycle_anchor's raw non-blank semantics
/// but yields no predictions, mirroring Kotlin's parse-failure nulls.
pub fn compute_anchor_state(
    last_period_start_date: &str,
    date_choice_raw: Option<&str>,
    cycle_length_choice_raw: Option<&str>,
    average_cycle_length: i32,
    today: NaiveDate,
) -> Option<(CycleAnchorState, Option<NaiveDate>)> {
    let (unknown_date, unknown_cl, approximate) = classify_choices(date_choice_raw, cycle_length_choice_raw);
    // Kotlin isNotBlank on the RAW settings string (no trim).
    let last_non_blank = !last_period_start_date.chars().all(kotlin_is_whitespace)
        && !last_period_start_date.is_empty();
    let has_anchor = last_non_blank && !unknown_date && !unknown_cl;
    if !has_anchor {
        return Some((
            CycleAnchorState {
                has_cycle_anchor: false,
                cycle_start_is_approximate: approximate,
                days_until_next_period: 0,
                next_period_date: None,
            },
            None,
        ));
    }
    // Full-parser admission (reviewer M9): hasCycleAnchor keeps the raw
    // non-blank semantics for EVERY string, but the countdown must not
    // invent values the Kotlin route would not produce.
    match parse_local_date_iso_full(last_period_start_date) {
        // Java-valid but outside chrono's range: Kotlin's LocalDate handles
        // it and computes real countdowns the native port cannot reproduce
        // -> unsupported, caller falls back (never a silent countdown-0).
        ParsedLocalDate::Unrepresentable { .. } => None,
        // Java-invalid (incl. "-0000", which Java rejects): Kotlin's
        // runCatching{LocalDate.parse} reads null, so nextPeriodDate is
        // null and the countdown is 0 while hasCycleAnchor stays true.
        ParsedLocalDate::Invalid => Some((
            CycleAnchorState {
                has_cycle_anchor: true,
                cycle_start_is_approximate: approximate,
                days_until_next_period: 0,
                next_period_date: None,
            },
            None,
        )),
        ParsedLocalDate::Representable(ls) => match next_period_after(ls, today, average_cycle_length) {
            Some(next) => {
                let days_until = (next - today).num_days().max(0) as i32;
                Some((
                    CycleAnchorState {
                        has_cycle_anchor: true,
                        cycle_start_is_approximate: approximate,
                        days_until_next_period: days_until,
                        next_period_date: Some(next),
                    },
                    Some(ls),
                ))
            }
            // Checked step overflow near the native range edge: Kotlin's
            // wider LocalDate range still computes -> unsupported.
            None => None,
        },
    }
}

/// Countdown block of CycleCareViewModel.updateCalculations (:491-508).
/// QUIRKS (ported 1:1, labeled):
///  - RAW settings.lutealPhaseDays / periodDuration (not the 10..16 / 2..10
///    clamps calculateDayPrediction applies); only averageCycleLength is
///    clamped (21..45).
///  - KOTLIN INT ARITHMETIC: subtraction/addition wrap on overflow and
///    ChronoUnit.DAYS.between(...).toInt() truncates to the low 32 bits, so
///    malformed persisted/synced settings (or absurd dates) can yield
///    NEGATIVE daysUntilOvulation and a wrapped-then-clamped-to-0
///    daysUntilNextPeriod. Probe-locked against JVM Int semantics
///    (tests/fixtures/countdowns/adv_jvm_expected.txt); UI bounds alone do
///    not exclude malformed stored values - inputs are NOT assumed clean.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnchorCountdowns {
    pub days_until_next_period: i32,
    pub ovulation_cycle_day: i32,
    pub days_until_ovulation: i32,
}

pub fn compute_countdowns(
    today: NaiveDate,
    next_period_date: Option<NaiveDate>,
    today_day_of_cycle: Option<i32>,
    average_cycle_length: i32,
    luteal_phase_days: i32,
    period_duration: i32,
) -> AnchorCountdowns {
    let cycle_length = average_cycle_length.clamp(21, 45);
    let days_until_next_period = next_period_date
        .map(|d| ((d - today).num_days() as i32).max(0)) // Kotlin toInt() truncation
        .unwrap_or(0);
    let ovulation_cycle_day = cycle_length
        .wrapping_sub(luteal_phase_days)
        .max(period_duration.wrapping_add(2));
    let days_until_ovulation = today_day_of_cycle
        .map(|day| {
            if day <= ovulation_cycle_day {
                ovulation_cycle_day.wrapping_sub(day)
            } else {
                cycle_length.wrapping_sub(day).wrapping_add(ovulation_cycle_day)
            }
        })
        .unwrap_or(0);
    AnchorCountdowns {
        days_until_next_period,
        ovulation_cycle_day,
        days_until_ovulation,
    }
}
