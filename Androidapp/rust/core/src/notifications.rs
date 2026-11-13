//! Notification timestamp + priority parsing.
//! Port of parseIsoTimestamp + explicitPriorityOrNormal in
//! data/notification/SupabaseNotificationService.kt (main@31b88f5).
//!
//! A string carrying its own offset (Z or +/-hh:mm) is honored; a bare
//! timestamp is server UTC regardless of device zone. Impossible dates and
//! trailing garbage are rejected. Unparseable input returns 0 so a malformed
//! row sorts oldest instead of masquerading as fresh. Urgency comes only from
//! an explicit sender-set priority.
use crate::kotlin_text::{kotlin_trim};

use chrono::{NaiveDate, NaiveTime, TimeZone, Utc};
use regex::Regex;
use std::sync::OnceLock;

fn iso_pattern() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$")
            .expect("iso pattern compiles")
    })
}

pub fn parse_iso_timestamp(iso_string: Option<&str>) -> i64 {
    let Some(raw) = iso_string else { return 0 };
    let trimmed = kotlin_trim(raw);
    if trimmed.is_empty() {
        return 0;
    }
    (|| {
        let caps = iso_pattern().captures(trimmed)?;
        let date = NaiveDate::parse_from_str(caps.get(1)?.as_str(), "%Y-%m-%d").ok()?;
        let time = NaiveTime::parse_from_str(caps.get(2)?.as_str(), "%H:%M:%S").ok()?;
        let mut millis = Utc.from_utc_datetime(&date.and_time(time)).timestamp_millis();
        if let Some(frac) = caps.get(3).map(|m| m.as_str()).filter(|s| !s.is_empty()) {
            // first 3 digits, right-padded with zeros: .5 -> 500, .123456 -> 123
            let mut ms = frac.chars().take(3).collect::<String>();
            while ms.len() < 3 {
                ms.push('0');
            }
            millis += ms.parse::<i64>().ok()?;
        }
        if let Some(zone) = caps.get(4).map(|m| m.as_str()).filter(|s| !s.is_empty() && *s != "Z") {
            let sign: i64 = if zone.starts_with('-') { -1 } else { 1 };
            let digits: String = zone[1..].chars().filter(|c| *c != ':').collect();
            let hours: i64 = digits.get(0..2)?.parse().ok()?;
            let minutes: i64 = digits.get(2..4)?.parse().ok()?;
            millis -= sign * (hours * 60 + minutes) * 60_000;
        }
        Some(millis)
    })()
    .unwrap_or(0)
}

/// Urgency must come from an explicit sender-set priority; a missing, blank
/// or unrecognized value must never render as urgent.
pub fn explicit_priority_or_normal(raw: Option<&str>) -> &'static str {
    match raw.map(|s| kotlin_trim(s).to_uppercase()).as_deref() {
        Some("URGENT") => "URGENT",
        Some("HIGH") => "HIGH",
        _ => "NORMAL",
    }
}
