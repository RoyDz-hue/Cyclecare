//! Mirrors NotificationParsingTest (Kotlin) case for case.

use cyclecare_core::notifications::*;

fn utc_millis(value: &str) -> i64 {
    use chrono::{NaiveDateTime, TimeZone, Utc};
    let dt = NaiveDateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S").unwrap();
    Utc.from_utc_datetime(&dt).timestamp_millis()
}

#[test]
fn zulu_timestamp_parses_as_utc() {
    assert_eq!(utc_millis("2026-10-31T08:15:00"), parse_iso_timestamp(Some("2026-10-31T08:15:00Z")));
}

#[test]
fn positive_offset_is_honored_not_forced_to_utc() {
    assert_eq!(utc_millis("2026-10-31T07:00:00"), parse_iso_timestamp(Some("2026-10-31T10:00:00+03:00")));
}

#[test]
fn negative_offset_without_colon_is_honored() {
    assert_eq!(utc_millis("2026-10-31T15:00:00"), parse_iso_timestamp(Some("2026-10-31T10:00:00-0500")));
}

#[test]
fn fractional_seconds_keep_millisecond_precision_for_same_second_ordering() {
    let first = parse_iso_timestamp(Some("2026-10-31T08:15:00.123456+00:00"));
    let second = parse_iso_timestamp(Some("2026-10-31T08:15:00.5Z"));
    assert_eq!(utc_millis("2026-10-31T08:15:00") + 123, first);
    assert_eq!(utc_millis("2026-10-31T08:15:00") + 500, second);
    assert!(second > first, "the later-created row must sort after the earlier one");
}

#[test]
fn bare_timestamp_is_treated_as_server_utc_regardless_of_device_zone() {
    // Device zone is irrelevant: no local clock is consulted.
    assert_eq!(utc_millis("2026-10-31T08:15:00"), parse_iso_timestamp(Some("2026-10-31T08:15:00")));
}

#[test]
fn impossible_dates_are_rejected_not_rolled_over() {
    assert_eq!(0, parse_iso_timestamp(Some("2026-13-01T08:15:00Z")));
    assert_eq!(0, parse_iso_timestamp(Some("2026-02-30T08:15:00Z")));
    assert_eq!(0, parse_iso_timestamp(Some("2026-10-31T25:15:00Z")));
}

#[test]
fn trailing_garbage_and_malformed_input_are_rejected() {
    assert_eq!(0, parse_iso_timestamp(Some("2026-10-31T08:15:00Zjunk")));
    assert_eq!(0, parse_iso_timestamp(Some("not-a-timestamp")));
    assert_eq!(0, parse_iso_timestamp(Some("2026-10-31 08:15:00")));
}

#[test]
fn null_blank_and_invalid_sort_oldest_instead_of_masquerading_as_fresh() {
    assert_eq!(0, parse_iso_timestamp(None));
    assert_eq!(0, parse_iso_timestamp(Some("")));
    assert_eq!(0, parse_iso_timestamp(Some("   ")));
    assert!(parse_iso_timestamp(Some("garbage")) < parse_iso_timestamp(Some("2020-01-01T00:00:00Z")));
}

#[test]
fn absent_blank_or_unknown_priority_is_never_urgent() {
    assert_eq!("NORMAL", explicit_priority_or_normal(None));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("")));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("   ")));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("CRITICAL")));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("LOW")));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("urgentish")));
}

#[test]
fn explicit_sender_priority_is_preserved_case_insensitively() {
    assert_eq!("URGENT", explicit_priority_or_normal(Some("urgent")));
    assert_eq!("HIGH", explicit_priority_or_normal(Some("High")));
    assert_eq!("NORMAL", explicit_priority_or_normal(Some("NORMAL")));
}
