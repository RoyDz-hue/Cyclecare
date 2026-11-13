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

    /// Verbatim CycleModels.kt enum ENTRY name (repo main@31b88f5) - the
    /// wire/API name, NOT the display title.
    pub fn kotlin_name(&self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "MENSTRUAL",
            CyclePhase::Follicular => "FOLLICULAR",
            CyclePhase::Ovulation => "OVULATION",
            CyclePhase::Luteal => "LUTEAL",
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

/// Lexical scan of the probe-locked ISO_LOCAL_DATE grammar (see
/// parse_local_date_iso): returns (year, month, day) with NO calendar or
/// range validation; year is i64 so the full Java +/-999999999 domain scans.
fn scan_iso_local_date(s: &str) -> Option<(i64, u32, u32)> {
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
    let year: i64 = std::str::from_utf8(year_digits).ok()?.parse().ok()?;
    let year = if neg { -year } else { year };
    let month: u32 = std::str::from_utf8(m).ok()?.parse().ok()?;
    let day: u32 = std::str::from_utf8(d).ok()?.parse().ok()?;
    Some((year, month, day))
}

/// Strict port of java.time.LocalDate.parse with ISO_LOCAL_DATE, proven by
/// probe (host JVM, same rules on ART): unsigned year is EXACTLY 4 digits;
/// a signed year ('+'/'-') must be 5-10 digits; month and day exactly 2
/// digits; no surrounding whitespace; validity via calendar rules.
/// chrono's "%Y-%m-%d" parse is lenient about zero padding, so it is NOT
/// used. Dates that are valid in Java but outside chrono NaiveDate's range
/// (about +/-262143; Java allows +/-999999999) return None here; callers
/// that must answer for the full Java domain use parse_local_date_iso_full,
/// which keeps them comparable instead of treating them as invalid input.
pub fn parse_local_date_iso(s: &str) -> Option<NaiveDate> {
    let (year, month, day) = scan_iso_local_date(s)?;
    let year: i32 = year.try_into().ok()?;
    NaiveDate::from_ymd_opt(year, month, day)
}

/// Full-domain outcome of the probe-locked ISO parse. java.time.LocalDate
/// admits years in +/-999999999 while chrono covers roughly +/-262143, so a
/// valid Java date can be unrepresentable as NaiveDate. It is still a real
/// calendar day: the JNI chronology check compares by epoch DAY - the unit
/// java.time's own LocalDate.compareTo uses; epoch millis would overflow
/// i64 at these year extremes - rather than reporting such input as invalid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParsedLocalDate {
    /// Inside chrono's range; the NaiveDate is authoritative.
    Representable(NaiveDate),
    /// Valid ISO calendar day outside chrono's range (e.g. +999999999-12-31).
    Unrepresentable { year: i64, month: u32, day: u32 },
    /// Not a valid ISO local date (lexical or calendar failure).
    Invalid,
}

/// Days from the civil epoch (1970-01-01), Howard Hinnant's algorithm.
/// Exact across the whole +/-999999999 Java domain (~3.65e11 days fits i64
/// easily, unlike epoch millis) and identical to chrono and java.time on
/// their overlapping range (proleptic Gregorian), so a Representable date
/// and an Unrepresentable date compare consistently.
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400; // [0, 399]
    let mp = (if m > 2 { m - 3 } else { m + 9 }) as i64; // Mar=0 .. Feb=11
    let doy = (153 * mp + 2) / 5 + (d as i64) - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    era * 146_097 + doe - 719_468
}

/// Proleptic Gregorian leap rule; valid for the full i64 year domain
/// (only divisibility by zero-remainder is tested, so truncation is safe).
fn is_leap_year_any(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_in_month_any(y: i64, m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year_any(y) => 29,
        2 => 28,
        _ => 0,
    }
}

/// Full-domain parse: same probe-locked grammar as parse_local_date_iso,
/// but a valid date outside chrono's range is kept as Unrepresentable
/// instead of collapsing to "invalid".
pub fn parse_local_date_iso_full(s: &str) -> ParsedLocalDate {
    let Some((year, month, day)) = scan_iso_local_date(s) else {
        return ParsedLocalDate::Invalid;
    };
    // Java parse compatibility: LocalDate years are bounded to
    // +/-999999999 (10-digit scan results beyond that are not valid Java
    // dates), and Java rejects negative zero ("-0000"). The legacy
    // parse_local_date_iso deliberately keeps its prior behavior.
    if !(-999_999_999..=999_999_999).contains(&year) {
        return ParsedLocalDate::Invalid;
    }
    if year == 0 && s.as_bytes().first() == Some(&b'-') {
        return ParsedLocalDate::Invalid;
    }
    if day == 0 || day > days_in_month_any(year, month) {
        return ParsedLocalDate::Invalid;
    }
    let representable = i32::try_from(year)
        .ok()
        .and_then(|y| NaiveDate::from_ymd_opt(y, month, day));
    match representable {
        Some(date) => ParsedLocalDate::Representable(date),
        None => ParsedLocalDate::Unrepresentable { year, month, day },
    }
}

impl ParsedLocalDate {
    /// Epoch day (days since 1970-01-01); None when not a valid date.
    pub fn epoch_day(self) -> Option<i64> {
        let (y, m, d) = match self {
            ParsedLocalDate::Representable(date) => {
                use chrono::Datelike;
                (date.year() as i64, date.month(), date.day())
            }
            ParsedLocalDate::Unrepresentable { year, month, day } => (year, month, day),
            ParsedLocalDate::Invalid => return None,
        };
        Some(days_from_civil(y, m, d))
    }
}

/// Full-domain chronology check (chronology only; future-date clinical
/// policy stays in PeriodDateConsistency). Identical answers to
/// validate_period_dates for chrono-representable dates, extended to valid
/// Java LocalDate extremes by epoch-day comparison - the same ordering
/// java.time's LocalDate.compareTo computes. None when either input is
/// null/not a valid date (caller maps that to false, as before).
pub fn validate_period_dates_full(start: ParsedLocalDate, end: ParsedLocalDate) -> Option<bool> {
    match (start.epoch_day(), end.epoch_day()) {
        (Some(s), Some(e)) => Some(e >= s),
        _ => None,
    }
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

    #[test]
    fn full_domain_boundaries() {
        use chrono::Datelike;
        // Java MAX/MIN years are valid ISO dates outside chrono's range.
        assert_eq!(
            parse_local_date_iso_full("+999999999-12-31"),
            ParsedLocalDate::Unrepresentable { year: 999_999_999, month: 12, day: 31 }
        );
        assert_eq!(
            parse_local_date_iso_full("-999999999-01-01"),
            ParsedLocalDate::Unrepresentable { year: -999_999_999, month: 1, day: 1 }
        );
        // +5..10 digit year inside chrono's range stays Representable.
        match parse_local_date_iso_full("+00123456-01-01") {
            ParsedLocalDate::Representable(d) => assert_eq!(d.year(), 123_456),
            other => panic!("expected Representable, got {other:?}"),
        }
        // Calendar-invalid dates are Invalid even at extreme years (Java
        // rejects them too: LocalDate.parse("+999999999-13-40") throws).
        assert_eq!(parse_local_date_iso_full("+999999999-13-40"), ParsedLocalDate::Invalid);
        assert_eq!(parse_local_date_iso_full("2026-02-29"), ParsedLocalDate::Invalid);
        assert_eq!(parse_local_date_iso_full("not-a-date"), ParsedLocalDate::Invalid);
        // Java domain enforcement: beyond +/-999999999 is not a Java date,
        // even though the probe-locked grammar scans 10-digit years.
        assert_eq!(parse_local_date_iso_full("+1000000000-01-01"), ParsedLocalDate::Invalid);
        assert_eq!(parse_local_date_iso_full("-1000000000-01-01"), ParsedLocalDate::Invalid);
        assert_eq!(parse_local_date_iso_full("+9999999999-12-31"), ParsedLocalDate::Invalid);
        assert_eq!(parse_local_date_iso_full("-9999999999-12-31"), ParsedLocalDate::Invalid);
        // Negative zero is rejected (Java rejects "-0000" as a year).
        assert_eq!(parse_local_date_iso_full("-0000-01-01"), ParsedLocalDate::Invalid);
        // The legacy parser is deliberately untouched by the tightening:
        // it still maps "-0000" to year 0 as before (documented divergence;
        // LocalDate.toString never emits "-0000", so JNI inputs never hit it).
        assert!(parse_local_date_iso("-0000-01-01").is_some());
        // Java MIN/MAX year boundaries themselves remain valid.
        assert_eq!(
            parse_local_date_iso_full("-999999999-12-31"),
            ParsedLocalDate::Unrepresentable { year: -999_999_999, month: 12, day: 31 }
        );
        // days_from_civil must agree with chrono on the overlapping domain,
        // including negative years and leap edges.
        for (y, m, d) in [(1970, 1, 1), (2026, 10, 20), (2000, 2, 29), (1900, 2, 28), (-1, 12, 31), (-400, 2, 29), (-262142, 6, 15)] {
            let date = NaiveDate::from_ymd_opt(y, m, d).unwrap();
            // Format like java.time.LocalDate.toString: unsigned 4-digit year
            // inside 0..=9999, signed (>=4 digit) otherwise.
            let s = if (0..=9999).contains(&y) {
                format!("{y:04}-{m:02}-{d:02}")
            } else {
                format!("{y:+05}-{m:02}-{d:02}")
            };
            let parsed = parse_local_date_iso_full(&s);
            assert_eq!(parsed, ParsedLocalDate::Representable(date), "{y}-{m}-{d}");
            assert_eq!(
                parsed.epoch_day(),
                Some(date.num_days_from_ce() as i64 - 719_163),
                "epoch-day mismatch {y}-{m}-{d}"
            );
        }
    }

    #[test]
    fn full_domain_chronology() {
        let ordinary = parse_local_date_iso_full("2026-10-20");
        let max = parse_local_date_iso_full("+999999999-12-31");
        let min = parse_local_date_iso_full("-999999999-01-01");
        // The reviewer's failing pair: previously authoritative false.
        assert_eq!(validate_period_dates_full(ordinary, max), Some(true));
        assert_eq!(validate_period_dates_full(max, max), Some(true)); // equal MAX pair
        assert_eq!(validate_period_dates_full(max, ordinary), Some(false));
        assert_eq!(validate_period_dates_full(min, max), Some(true));
        assert_eq!(
            validate_period_dates_full(min, parse_local_date_iso_full("-999999998-12-31")),
            Some(true)
        );
        // Invalid/null inputs stay None -> false downstream, as before.
        assert_eq!(validate_period_dates_full(ParsedLocalDate::Invalid, max), None);
        assert_eq!(validate_period_dates_full(ordinary, ParsedLocalDate::Invalid), None);
        // Identical answers to the chrono-only helper on representable pairs.
        let a = parse_local_date_iso("2026-10-24");
        let b = parse_local_date_iso("2026-10-20");
        assert_eq!(
            validate_period_dates(a, b),
            validate_period_dates_full(parse_local_date_iso_full("2026-10-24"), parse_local_date_iso_full("2026-10-20")).unwrap()
        );
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
