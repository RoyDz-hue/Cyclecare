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

// ---------- conversions to/from the sync wire model ----------
// Production has ONE CycleDayLog data class; this port keeps the Room
// entity (verbatim field names, Kotlin Float=f32/Int=i32) here and the
// health-sync wire view in sync_codec. Convert at the boundary.

impl From<&CycleDayLog> for crate::sync_codec::CycleDayLog {
    fn from(log: &CycleDayLog) -> Self {
        crate::sync_codec::CycleDayLog {
            date: log.date_string.clone(),
            flow: log.flow_level.clone(),
            energy: log.energy_level.clone(),
            mood: log.mood_level.clone(),
            symptoms: log.symptoms_csv.clone(),
            vaginal_health: log.vaginal_health_csv.clone(),
            urinary_health: log.urinary_health_csv.clone(),
            cervical_mucus: log.cervical_mucus.clone(),
            ovulation_test: log.ovulation_test.clone(),
            bbt_celsius: log.bbt_celsius,
            sleep_hours: log.sleep_hours,
            sleep_quality: log.sleep_quality.clone(),
            water_glasses: log.water_glasses,
            notes: log.notes.clone(),
            pain_intensity: log.pain_intensity,
        }
    }
}

impl From<&crate::sync_codec::CycleDayLog> for CycleDayLog {
    fn from(w: &crate::sync_codec::CycleDayLog) -> Self {
        CycleDayLog {
            date_string: w.date.clone(),
            flow_level: w.flow.clone(),
            energy_level: w.energy.clone(),
            mood_level: w.mood.clone(),
            symptoms_csv: w.symptoms.clone(),
            vaginal_health_csv: w.vaginal_health.clone(),
            urinary_health_csv: w.urinary_health.clone(),
            cervical_mucus: w.cervical_mucus.clone(),
            ovulation_test: w.ovulation_test.clone(),
            bbt_celsius: w.bbt_celsius,
            sleep_hours: w.sleep_hours,
            sleep_quality: w.sleep_quality.clone(),
            water_glasses: w.water_glasses,
            notes: w.notes.clone(),
            pain_intensity: w.pain_intensity,
        }
    }
}
