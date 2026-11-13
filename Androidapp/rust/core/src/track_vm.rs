//! Port of TrackScreen.kt's state holders + candidate-log construction
//! (repo main@31b88f5, TrackScreenContent L94-165). The ViewModel store and
//! repository save are NOT part of this port; this is the exact per-day edit
//! state the screen composes, including its production quirks:
//! - observation_count counts ovulationTest "" as noted (Kotlin `!= "NONE"`,
//!   no empty check) - parity/safety-review quirk, ported verbatim.
//! - comfort_count reads painRating > 0 and waterCount > 0, NOT the recorded
//!   flags - parity quirk, ported verbatim.
//! - symptom/vaginal/urinary selections are LinkedHashSet-ordered
//!   (insertion order, re-adding keeps position).
//! Semantics locked against the JVM probe (tests/track_vm_corpus.rs).

use crate::kotlin_text::kotlin_is_whitespace;
use crate::safety::{self, SafetyEvaluation, SafetyLog};
use crate::track_log::CycleDayLog;

fn not_blank(s: &str) -> bool {
    s.chars().any(|c| !kotlin_is_whitespace(c))
}

/// Ordered string set with Kotlin LinkedHashSet +/- semantics.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct OrderedSet(Vec<String>);

impl OrderedSet {
    pub fn from_csv(csv: &str) -> Self {
        // split(",").filter(String::isNotBlank).toSet()
        let mut v = Vec::new();
        for part in csv.split(',') {
            if not_blank(part) && !v.iter().any(|x: &String| x == part) {
                v.push(part.to_string());
            }
        }
        OrderedSet(v)
    }

    pub fn toggle(&mut self, id: &str) {
        if let Some(pos) = self.0.iter().position(|x| x == id) {
            self.0.remove(pos);
        } else {
            self.0.push(id.to_string());
        }
    }

    pub fn contains(&self, id: &str) -> bool {
        self.0.iter().any(|x| x == id)
    }

    pub fn len(&self) -> usize {
        self.0.len()
    }

    pub fn join_csv(&self) -> String {
        self.0.join(",")
    }
}

/// Verbatim per-day edit state (the `remember` holders of
/// TrackScreenContent). Expanders and savedFeedback stay UI-side.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackDayState {
    pub selected_flow: String,
    pub selected_energy: String,
    pub selected_mood: String,
    pub selected_symptoms: OrderedSet,
    pub selected_vaginal: OrderedSet,
    pub selected_urinary: OrderedSet,
    pub selected_mucus: String,
    pub selected_ovulation_test: String,
    pub pain_rating: i32,
    pub pain_recorded: bool,
    /// Slider value; 7.5 when unrecorded (Kotlin default).
    pub sleep_hours: f32,
    pub sleep_hours_recorded: bool,
    pub selected_sleep_quality: String,
    pub water_count: i32,
    pub water_recorded: bool,
    pub notes_text: String,
}

impl Default for TrackDayState {
    fn default() -> Self {
        TrackDayState {
            selected_flow: String::new(),
            selected_energy: String::new(),
            selected_mood: String::new(),
            selected_symptoms: OrderedSet::default(),
            selected_vaginal: OrderedSet::default(),
            selected_urinary: OrderedSet::default(),
            selected_mucus: String::new(),
            selected_ovulation_test: "NONE".to_string(),
            pain_rating: 0,
            pain_recorded: false,
            sleep_hours: 7.5,
            sleep_hours_recorded: false,
            selected_sleep_quality: String::new(),
            water_count: 0,
            water_recorded: false,
            notes_text: String::new(),
        }
    }
}

impl TrackDayState {
    /// The LaunchedEffect(selectedDate, currentLog) reset: every holder
    /// re-initializes from the stored log (or defaults).
    pub fn reset_from_log(current_log: Option<&CycleDayLog>) -> Self {
        let mut s = TrackDayState::default();
        if let Some(log) = current_log {
            s.selected_flow = log.flow_level.clone();
            s.selected_energy = log.energy_level.clone();
            s.selected_mood = log.mood_level.clone();
            s.selected_symptoms = OrderedSet::from_csv(&log.symptoms_csv);
            s.selected_vaginal = OrderedSet::from_csv(&log.vaginal_health_csv);
            s.selected_urinary = OrderedSet::from_csv(&log.urinary_health_csv);
            s.selected_mucus = log.cervical_mucus.clone();
            s.selected_ovulation_test = log.ovulation_test.clone();
            s.pain_rating = log.pain_intensity;
            s.pain_recorded = log.pain_intensity > 0;
            if log.sleep_hours > 0.0 {
                s.sleep_hours = log.sleep_hours;
                s.sleep_hours_recorded = true;
            }
            s.selected_sleep_quality = log.sleep_quality.clone();
            s.water_count = log.water_glasses;
            s.water_recorded = log.water_glasses > 0;
            s.notes_text = log.notes.clone();
        }
        s
    }

    /// Selecting the same mucus option again clears it (Kotlin
    /// `if (selectedMucus == option) "" else option`).
    pub fn toggle_mucus(&mut self, option: &str) {
        self.selected_mucus = if self.selected_mucus == option {
            String::new()
        } else {
            option.to_string()
        };
    }

    pub fn set_pain(&mut self, rating: i32) {
        self.pain_rating = rating;
        self.pain_recorded = true;
    }

    pub fn set_sleep_hours(&mut self, hours: f32) {
        self.sleep_hours = hours;
        self.sleep_hours_recorded = true;
    }

    pub fn increment_water(&mut self) {
        self.water_count += 1;
        self.water_recorded = true;
    }

    pub fn decrement_water(&mut self) {
        self.water_count = (self.water_count - 1).max(0);
        self.water_recorded = true;
    }

    /// Verbatim candidateLog construction. bbtCelsius is never settable from
    /// this screen, so it stays 0f (parity note).
    pub fn candidate_log(&self, date_string: &str) -> CycleDayLog {
        let mut log = CycleDayLog::new(date_string);
        log.flow_level = self.selected_flow.clone();
        log.energy_level = self.selected_energy.clone();
        log.mood_level = self.selected_mood.clone();
        log.symptoms_csv = self.selected_symptoms.join_csv();
        log.vaginal_health_csv = self.selected_vaginal.join_csv();
        log.urinary_health_csv = self.selected_urinary.join_csv();
        log.cervical_mucus = self.selected_mucus.clone();
        log.ovulation_test = self.selected_ovulation_test.clone();
        log.sleep_hours = if self.sleep_hours_recorded { self.sleep_hours } else { 0.0 };
        log.sleep_quality = self.selected_sleep_quality.clone();
        log.water_glasses = if self.water_recorded { self.water_count } else { 0 };
        log.notes = self.notes_text.clone();
        log.pain_intensity = if self.pain_recorded { self.pain_rating } else { 0 };
        log
    }

    pub fn has_any_entry(&self, date_string: &str) -> bool {
        self.candidate_log(date_string).has_recorded_observations()
    }

    /// Verbatim observationCount (parity quirk: ovulationTest "" counts).
    pub fn observation_count(&self) -> usize {
        self.selected_vaginal.len()
            + self.selected_urinary.len()
            + if not_blank(&self.selected_mucus) { 1 } else { 0 }
            + if self.selected_ovulation_test != "NONE" { 1 } else { 0 }
    }

    /// Verbatim comfortCount (parity quirk: pain/water read the raw values,
    /// not the recorded flags).
    pub fn comfort_count(&self) -> usize {
        (if self.pain_rating > 0 { 1 } else { 0 })
            + (if self.sleep_hours_recorded { 1 } else { 0 })
            + (if not_blank(&self.selected_sleep_quality) { 1 } else { 0 })
            + (if self.water_count > 0 { 1 } else { 0 })
            + (if not_blank(&self.notes_text) { 1 } else { 0 })
    }

    /// SafetyEngine.evaluate(candidateLog) - the screen always passes a
    /// non-null candidate.
    pub fn evaluate_safety(&self, date_string: &str) -> SafetyEvaluation {
        let c = self.candidate_log(date_string);
        safety::evaluate(Some(&SafetyLog {
            flow_level: c.flow_level,
            symptoms_csv: c.symptoms_csv,
            vaginal_health_csv: c.vaginal_health_csv,
            urinary_health_csv: c.urinary_health_csv,
            pain_intensity: c.pain_intensity,
        }))
    }
}
