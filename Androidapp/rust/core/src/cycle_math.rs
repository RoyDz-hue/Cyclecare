//! Cycle calendar math and period-date consistency.
//! Ports of CycleRepository.calculateDayPrediction +
//! domain/health/PeriodDateConsistency.kt (main@31b88f5). Thresholds and
//! messages are verbatim from the source, which grounds them in ACOG patient
//! guidance; nothing here adds new clinical claims.
use crate::kotlin_text::{kotlin_trim};

use chrono::NaiveDate;

pub const MAX_TYPICAL_BLEEDING_DAYS: i64 = 7;

pub const CHRONOLOGY_MESSAGE: &str =
    "The end date can't be before the start date. Please check the dates.";
pub const FUTURE_DATE_MESSAGE: &str = "That date is in the future. Please check the dates.";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CyclePhase {
    Menstrual,
    Follicular,
    Ovulation,
    Luteal,
}

impl CyclePhase {
    /// Verbatim CycleModels.kt titles (repo main@31b88f5).
    pub fn kotlin_title(&self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "Menstruation",
            CyclePhase::Follicular => "Follicular Phase",
            CyclePhase::Ovulation => "Fertile & Ovulation",
            CyclePhase::Luteal => "Luteal Phase",
        }
    }

    /// Verbatim CycleModels.kt subtitle (repo main@31b88f5).
    pub fn kotlin_subtitle(&self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "Rest & Reset",
            CyclePhase::Follicular => "Energy Rising",
            CyclePhase::Ovulation => "Peak Fertility",
            CyclePhase::Luteal => "Nurture & Balance",
        }
    }

    /// Verbatim CycleModels.kt description (repo main@31b88f5).
    pub fn kotlin_description(&self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "Uterine lining sheds as hormone levels reach baseline.",
            CyclePhase::Follicular => "Estrogen gradually rises, prompting egg follicles to develop.",
            CyclePhase::Ovulation => "Luteinizing hormone surge triggers the release of a mature ovum.",
            CyclePhase::Luteal => "Progesterone peaks to support the uterine lining, then declines if unfertilized.",
        }
    }

    /// Verbatim CycleModels.kt advice (repo main@31b88f5).
    pub fn kotlin_advice(&self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "Prioritize restorative rest, gentle stretching, and iron-rich nourishing foods.",
            CyclePhase::Follicular => "Embrace rising stamina with creative projects, social connection, and active workouts.",
            CyclePhase::Ovulation => "Highest fertility window. Energy and mood vary from person to person; notice what is true for you.",
            CyclePhase::Luteal => "Incorporate magnesium, complex carbohydrates, warm herbal infusions, and mindful pacing.",
        }
    }

    /// Verbatim CycleModels.kt colorHex (0xAARRGGBB, repo main@31b88f5).
    pub fn kotlin_color_hex(&self) -> u32 {
        match self {
            CyclePhase::Menstrual => 0xFF8A3B4A,
            CyclePhase::Follicular => 0xFF55745F,
            CyclePhase::Ovulation => 0xFF9B6B2C,
            CyclePhase::Luteal => 0xFF695873,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DayPrediction {
    pub date: NaiveDate,
    pub day_of_cycle: i32,
    pub phase: CyclePhase,
    pub is_period: bool,
    pub is_fertile: bool,
    pub is_ovulation: bool,
    pub fertility_confidence: &'static str,
    pub pregnancy_chance: &'static str,
}

/// Port of calculateDayPrediction. Settings values are the already-stored
/// user settings; a blank or unparseable last period start yields None.

/// Strict port of java.time.LocalDate.parse with ISO_LOCAL_DATE, proven by
/// probe (host JVM, same rules on ART): unsigned year is EXACTLY 4 digits;
/// a signed year ('+'/'-') must be 5-10 digits; month and day exactly 2
/// digits; no surrounding whitespace; validity via calendar rules.
/// chrono's "%Y-%m-%d" parse is lenient about zero padding, so it is NOT
/// used. Years beyond chrono's i32 range (+/-999999999 in Java) cannot be
/// represented here when outside chrono NaiveDate's range (about +/-262143;
/// Java allows +/-999999999 - e.g. "-123456789-01-01" parses on the JVM but
/// is rejected here); the app never produces them (dates come from
/// LocalDate.toString of real device dates).
pub fn parse_local_date_iso(s: &str) -> Option<NaiveDate> {
    let bytes = s.as_bytes();
    // Probe-locked asymmetry (EXCEEDS_PAD): unsigned year is exactly 4
    // digits; '-' allows 4-10 digits; '+' requires 5-10 digits.
    let (neg, plus, rest) = match bytes.first() {
        Some(b'+') => (false, true, &bytes[1..]),
        Some(b'-') => (true, false, &bytes[1..]),
        _ => (false, false, bytes),
    };
    let signed = neg || plus;
    let dash1 = rest.iter().position(|&b| b == b'-')?;
    let year_digits = &rest[..dash1];
    let valid_year_len = if plus {
        (5..=10).contains(&year_digits.len())
    } else if signed {
        (4..=10).contains(&year_digits.len())
    } else {
        year_digits.len() == 4
    };
    if !valid_year_len || !year_digits.iter().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let after = &rest[dash1 + 1..];
    if after.len() != 5 || after[2] != b'-' {
        return None;
    }
    let (m, d) = (&after[..2], &after[3..]);
    if !m.iter().all(|b| b.is_ascii_digit()) || !d.iter().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let year: i32 = std::str::from_utf8(year_digits).ok()?.parse().ok()?;
    let year = if neg { -year } else { year };
    let month: u32 = std::str::from_utf8(m).ok()?.parse().ok()?;
    let day: u32 = std::str::from_utf8(d).ok()?.parse().ok()?;
    NaiveDate::from_ymd_opt(year, month, day)
}

pub fn calculate_day_prediction(
    date: NaiveDate,
    last_period_start_date: &str,
    average_cycle_length: i32,
    period_duration: i32,
    luteal_phase_days: i32,
) -> Option<DayPrediction> {
    if last_period_start_date.is_empty() {
        return None;
    }
    let last_start = parse_local_date_iso(last_period_start_date)?;

    let cycle_length = average_cycle_length.clamp(21, 45);
    let period_duration = period_duration.clamp(2, 10);
    let luteal_length = luteal_phase_days.clamp(10, 16);

    let ovulation_cycle_day = (cycle_length - luteal_length).max(period_duration + 2);
    let fertile_start_cycle_day = (ovulation_cycle_day - 4).max(period_duration + 1);
    let fertile_end_cycle_day = (ovulation_cycle_day + 1).min(cycle_length);

    let days_difference = (date - last_start).num_days();
    let cycle_day_zero_indexed = days_difference.rem_euclid(cycle_length as i64);
    let cycle_day = cycle_day_zero_indexed as i32 + 1;

    let is_period = cycle_day <= period_duration;
    let is_ovulation = cycle_day == ovulation_cycle_day;
    let is_fertile = (fertile_start_cycle_day..=fertile_end_cycle_day).contains(&cycle_day);

    let phase = if is_period {
        CyclePhase::Menstrual
    } else if cycle_day < fertile_start_cycle_day {
        CyclePhase::Follicular
    } else if cycle_day <= fertile_end_cycle_day {
        CyclePhase::Ovulation
    } else {
        CyclePhase::Luteal
    };

    Some(DayPrediction {
        date,
        day_of_cycle: cycle_day,
        phase,
        is_period,
        is_fertile,
        is_ovulation,
        fertility_confidence: "Calendar Estimate",
        pregnancy_chance: "Not calculated from calendar dates",
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    Typical,
    UnusualConfirm,
}

impl Verdict {
    /// Name matching the Kotlin enum constant (used for cross-language parity).
    pub fn kotlin_name(&self) -> &'static str {
        match self {
            Verdict::Typical => "TYPICAL",
            Verdict::UnusualConfirm => "UNUSUAL_CONFIRM",
        }
    }
}

pub fn verdict_for(duration_days: i64) -> Verdict {
    if duration_days > MAX_TYPICAL_BLEEDING_DAYS {
        Verdict::UnusualConfirm
    } else {
        Verdict::Typical
    }
}

/// Gentle confirmation wording for an unusually long period. Must not promise
/// the entry is kept - saving happens only after the user confirms.
/// `is_ongoing` distinguishes a period still in progress (firmer guidance)
/// from a historical, ended episode. Verbatim from the Kotlin source.
pub fn unusual_message(duration_days: i64, is_ongoing: bool) -> String {
    if is_ongoing {
        format!("Your period has lasted {duration_days} days so far. That's longer than usual. Please check the start date. If it's right, it's worth speaking with a healthcare professional soon.")
    } else {
        format!("Your period lasted {duration_days} days. That's longer than usual. Please check the dates. If they're right, it's worth mentioning to a healthcare professional.")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeriodPairCheck {
    pub is_valid: bool,
    pub error_message: Option<&'static str>,
    pub warning_message: Option<String>,
    pub duration_days: i64,
}

fn invalid(msg: &'static str) -> PeriodPairCheck {
    PeriodPairCheck { is_valid: false, error_message: Some(msg), warning_message: None, duration_days: 0 }
}

/// Pair validation: only chronology violations and future dates are hard
/// errors. Every real duration is accepted; unusual durations carry a
/// confirmation warning. Nothing here silently rewrites what the user
/// entered. (Error strings verbatim from the Kotlin source, including the
/// two that differ from the shared CHRONOLOGY/FUTURE constants above.)
pub fn validate_pair(
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    today: NaiveDate,
    is_ongoing: bool,
) -> PeriodPairCheck {
    let Some(start) = start else { return invalid("Period start date is required") };
    let Some(end) = end else { return invalid("Period end date is required") };
    if end < start {
        return invalid("Period end date cannot be earlier than start date");
    }
    if start > today || end > today {
        return invalid("Period dates cannot be in the future");
    }
    let duration = (end - start).num_days() + 1;
    PeriodPairCheck {
        is_valid: true,
        error_message: None,
        warning_message: if verdict_for(duration) == Verdict::UnusualConfirm {
            Some(unusual_message(duration, is_ongoing))
        } else {
            None
        },
        duration_days: duration,
    }
}

/// Port of validatePeriodDates: both dates present and end not before start.
pub fn validate_period_dates(start: Option<NaiveDate>, end: Option<NaiveDate>) -> bool {
    match (start, end) {
        (Some(s), Some(e)) => e >= s,
        _ => false,
    }
}

/// Port of validatePeriodDateStrings: blank/unparseable -> false.
pub fn validate_period_date_strings(start: Option<&str>, end: Option<&str>) -> bool {
    let parse = |s: Option<&str>| {
        s.filter(|v| !kotlin_trim(v).is_empty())
            .and_then(parse_local_date_iso)
    };
    validate_period_dates(parse(start), parse(end))
}

// Note: `today` is always supplied by the caller (the Android adapter reads
// the device clock), keeping this crate deterministic and clock-free.

#[cfg(test)]
mod probe_locked_date_tests {
    use super::*;

    #[test]
    fn java_iso_local_date_rules() {
        // Executed on JVM (kotlinc 2.1.0 / JRE 21): identical verdicts.
        assert!(parse_local_date_iso("2026-01-05").is_some());
        assert_eq!(parse_local_date_iso("2026-1-5"), None);
        assert_eq!(parse_local_date_iso("2026-1-15"), None);
        assert_eq!(parse_local_date_iso("2026-11-5"), None);
        assert_eq!(parse_local_date_iso("2026-13-01"), None);
        assert_eq!(parse_local_date_iso("2026-02-30"), None);
        assert_eq!(parse_local_date_iso("10000-01-01"), None);
        assert!(parse_local_date_iso("+12345-01-01").is_some());
        assert!(parse_local_date_iso("0000-01-01").is_some());
        assert!(parse_local_date_iso("-0001-01-01").is_some());
        assert_eq!(parse_local_date_iso("+123-01-01"), None);
        assert_eq!(parse_local_date_iso("+0123-01-01"), None);
        assert_eq!(parse_local_date_iso(" 2026-01-05"), None);
        assert_eq!(parse_local_date_iso("2026-01-05 "), None);
    }
}

/// Port of domain/health/PeriodEpisodeEntry.kt (main@31b88f5): what
/// onboarding may store for a start/end pair. Raw episode record stays
/// separate from the typical-duration estimate; unusual durations preserve
/// the raw end date but never drag the estimate.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StorageDecision {
    pub raw_end_date: Option<NaiveDate>,
    pub typical_duration_days: i64,
    pub requires_confirmation: bool,
    pub warning_message: Option<String>,
    pub error_message: Option<&'static str>,
}

pub fn decide_episode_storage(
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    is_ongoing: bool,
    current_typical_duration_days: i64,
    today: NaiveDate,
) -> StorageDecision {
    let check = validate_pair(start, end, today, is_ongoing);
    if !check.is_valid {
        return StorageDecision {
            raw_end_date: None,
            typical_duration_days: current_typical_duration_days,
            requires_confirmation: false,
            warning_message: None,
            error_message: check.error_message,
        };
    }
    let unusual = check.warning_message.is_some();
    StorageDecision {
        raw_end_date: end,
        typical_duration_days: if unusual {
            current_typical_duration_days
        } else {
            check.duration_days
        },
        requires_confirmation: unusual,
        warning_message: check.warning_message,
        error_message: None,
    }
}

/// PeriodDateRange (CycleRepository.kt line 28).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeriodDateRange {
    pub period_start_date: NaiveDate,
    pub period_end_date: NaiveDate,
}

impl PeriodDateRange {
    /// Inclusive duration, always at least 1 day.
    pub fn duration_days(&self) -> i64 {
        ((self.period_end_date - self.period_start_date).num_days() + 1).max(1)
    }
    pub fn is_valid(&self) -> bool {
        self.period_end_date >= self.period_start_date
    }
    /// Kotlin LocalDate.parse of Room's stored "YYYY-MM-DD" strings; any
    /// parse failure is null (Kotlin catches all exceptions).
    pub fn from_strings(start: &str, end: &str) -> Option<PeriodDateRange> {
        let s = parse_local_date_iso(start)?;
        let e = parse_local_date_iso(end)?;
        Some(PeriodDateRange {
            period_start_date: s,
            period_end_date: e,
        })
    }
}

/// CycleRepository.PeriodDateValidationResult.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeriodDateValidationResult {
    pub is_valid: bool,
    pub error_message: Option<String>,
    pub duration_days: i64,
    pub range: Option<PeriodDateRange>,
    pub warning_message: Option<String>,
}

/// CycleRepository.Companion.validatePeriodDatePair: delegates to
/// PeriodDateConsistency.validatePair and attaches the valid range.
pub fn validate_period_date_pair(
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    is_ongoing: bool,
    today: NaiveDate,
) -> PeriodDateValidationResult {
    let check = validate_pair(start, end, today, is_ongoing);
    PeriodDateValidationResult {
        is_valid: check.is_valid,
        error_message: check.error_message.map(|e| e.to_string()),
        duration_days: check.duration_days,
        range: if check.is_valid {
            match (start, end) {
                (Some(s), Some(e)) => Some(PeriodDateRange {
                    period_start_date: s,
                    period_end_date: e,
                }),
                _ => None,
            }
        } else {
            None
        },
        warning_message: check.warning_message,
    }
}
