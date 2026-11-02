//! Port of data/local/CycleDayLog.kt (repo main@31b88f5) - the daily
//! check-in record behind TrackScreen. Semantics locked against the JVM
//! (kotlinc 2.1.0/JRE 21 probe, 12 rows in tests/track_log_corpus.rs):
//! blank strings are Kotlin isBlank (kotlin_is_whitespace INCLUDES
//! NBSP/2007/202F, so an NBSP-only field counts as not recorded), and the
//! zero/negative numeric defaults count as not recorded.
//!
//! Empty string = not reported for the enum-backed fields; the enum id sets
//! themselves belong to the TrackScreen port.

use crate::kotlin_text::kotlin_is_whitespace;

#[derive(Debug, Clone, PartialEq)]
pub struct CycleDayLog {
    /// Format: YYYY-MM-DD (primary key in Room).
    pub date_string: String,
    /// Empty = not reported; FlowLevel enum name when selected.
    pub flow_level: String,
    pub energy_level: String,
    pub mood_level: String,
    /// Comma-separated symptom ids.
    pub symptoms_csv: String,
    pub vaginal_health_csv: String,
    pub urinary_health_csv: String,
    pub cervical_mucus: String,
    /// OvulationTestResult enum name; "NONE" or "" = not reported.
    pub ovulation_test: String,
    pub bbt_celsius: f32,
    /// Zero = not reported.
    pub sleep_hours: f32,
    pub sleep_quality: String,
    pub water_glasses: i32,
    pub notes: String,
    /// 0..=10; zero = not reported.
    pub pain_intensity: i32,
}

impl CycleDayLog {
    /// Kotlin data-class defaults for a fresh day.
    pub fn new(date_string: &str) -> Self {
        CycleDayLog {
            date_string: date_string.to_string(),
            flow_level: String::new(),
            energy_level: String::new(),
            mood_level: String::new(),
            symptoms_csv: String::new(),
            vaginal_health_csv: String::new(),
            urinary_health_csv: String::new(),
            cervical_mucus: String::new(),
            ovulation_test: "NONE".to_string(),
            bbt_celsius: 0.0,
            sleep_hours: 0.0,
            sleep_quality: String::new(),
            water_glasses: 0,
            notes: String::new(),
            pain_intensity: 0,
        }
    }

    /// Verbatim CycleDayLog.hasRecordedObservations: Kotlin isNotBlank per
    /// field, ovulationTest !in setOf("", "NONE"), numerics strictly > 0.
    pub fn has_recorded_observations(&self) -> bool {
        fn not_blank(s: &str) -> bool {
            // Kotlin CharSequence.isNotBlank: any char NOT kotlin_is_whitespace.
            s.chars().any(|c| !kotlin_is_whitespace(c))
        }
        not_blank(&self.flow_level)
            || not_blank(&self.energy_level)
            || not_blank(&self.mood_level)
            || not_blank(&self.symptoms_csv)
            || not_blank(&self.vaginal_health_csv)
            || not_blank(&self.urinary_health_csv)
            || not_blank(&self.cervical_mucus)
            || (self.ovulation_test != "" && self.ovulation_test != "NONE")
            || self.bbt_celsius > 0.0
            || self.sleep_hours > 0.0
            || not_blank(&self.sleep_quality)
            || self.water_glasses > 0
            || not_blank(&self.notes)
            || self.pain_intensity > 0
    }
}
