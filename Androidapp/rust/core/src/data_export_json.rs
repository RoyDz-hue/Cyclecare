//! Portable port of `CycleCareViewModel.buildDataExportJson` (:1988-2046,
//! main 31b88f5): the org.json assembly and `toString(2)` serialization.
//! Writer semantics verified against the REAL AOSP org.json classes
//! (/downloads/aosp-json, Android libcore) executed on jdk-17.0.21+9:
//!   - insertion order, `"key": value` with a space, 2-space indent levels,
//!     empty object `{}` / empty array `[]`;
//!   - AOSP quote(): escapes '"', '\\' and EVERY '/' with a backslash
//!     (oracle-verified: "slash/solidus" -> "slash\\/solidus"), \b \t \n \f \r
//!     as the short forms, other chars <= 0x1F as \u00xx (lowercase hex),
//!     everything else RAW (0x7F, '<', '&', non-ASCII all unescaped);
//!   - numberToString (bytecode-verified in org.json.JSONObject): null ->
//!     throw; checkDouble throws on NaN/Infinity (JSONException); boxed
//!     Double -0.0 -> "-0"; integral numbers print via longValue (Long
//!     string, e.g. 36.0f -> "36", 1e7f -> "10000000", -0.0f -> "0");
//!     non-integral Float -> Float.toString (e.g. "1.5E-4", "1.4E-45").
//! Kotlin throw parity: NaN/Infinity in a float field aborts the export
//! with Err(NonFiniteFloat), matching the JSONException the source throws.
//! No per-key null removal occurs: every exported field is non-nullable in
//! CycleSettings/OnboardingProfile/CycleDayLog (grep-verified); only the
//! WHOLE profile may be absent (-> empty object).
//! Known labeled limits: Float.toString digits are Java-verified only in
//! the plain range [1e-3, 1e7) (see data_export_pdf::java_float_to_string);
//! non-integral floats outside that range use Rust's shortest digits with
//! Java's sci presentation - corpus-covered for 1.5E-4 but NOT
//! tie-rule-verified, and Java's SUBNORMAL digit generation diverges:
//! Float.MIN_VALUE prints "1.4E-45" in Java vs "1.0E-45" here (asserted
//! divergence in the corpus, case x34). bbt/sleep cannot physically hold
//! such magnitudes; the gap is labeled, not hidden. Log sorting uses Kotlin sortedBy(dateString), a
//! UTF-16 code-unit order; this port's byte order matches for ASCII dates
//! and can differ only for astral characters in a date string.

use crate::data_export_pdf::java_float_to_string;

pub struct ExportSettings {
    pub selected_goals_csv: String,
    pub last_period_start_date: String,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub remind_period: bool,
    pub remind_fertile: bool,
    pub remind_daily_log: bool,
    pub is_discreet_notification_mode: bool,
}

pub struct ExportProfile {
    pub selected_goals_csv: String,
    pub last_period_date_string: String,
    pub last_period_end_date_string: String,
    pub approximate_period_choice: String,
    pub cycle_length_option: String,
    pub cycle_length_days: i32,
    pub period_duration_days: i32,
    pub remind_fertile_window: bool,
    pub typical_symptoms_csv: String,
    pub typical_energy: String,
    pub typical_sleep_quality: String,
}

pub struct ExportLog {
    pub date_string: String,
    pub flow_level: String,
    pub energy_level: String,
    pub mood_level: String,
    pub symptoms_csv: String,
    pub vaginal_health_csv: String,
    pub urinary_health_csv: String,
    pub cervical_mucus: String,
    pub ovulation_test: String,
    pub bbt_celsius: f32,
    pub sleep_hours: f32,
    pub sleep_quality: String,
    pub water_glasses: i32,
    pub notes: String,
    pub pain_intensity: i32,
}

/// The source throws org.json.JSONException on NaN/Infinity; the Kotlin
/// caller sees the export fail. No silent substitution.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportError {
    NonFiniteFloat,
}

/// AOSP JSONObject.quote, verified against oracle cases (x1-x18).
fn aosp_quote(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' | '\\' | '/' => {
                out.push('\\');
                out.push(c);
            }
            '\u{8}' => out.push_str("\\b"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\u{c}' => out.push_str("\\f"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) <= 0x1f => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// AOSP JSONObject.numberToString for a Kotlin Float field.
/// checkDouble (NaN/Inf) -> Err; integral -> longValue string; else
/// Float.toString (plain range verified, sci presentation corpus-covered).
fn float_number_to_string(out: &mut String, v: f32) -> Result<(), ExportError> {
    if v.is_nan() || v.is_infinite() {
        return Err(ExportError::NonFiniteFloat);
    }
    let l = v as i64; // Java Float.longValue: truncating, saturating
    if (v as f64) == (l as f64) {
        out.push_str(&l.to_string());
        return Ok(());
    }
    let a = v.abs();
    if a != 0.0 && !(1e-3..1e7).contains(&a) {
        // Java Float.toString uses sci presentation outside [1e-3, 1e7):
        // d.dddE<exp> (no '+' on positive exponents). Digits come from
        // Rust's shortest-round-trip {:e}; Java's digit choice is
        // corpus-covered for 1.5E-4 but NOT tie-rule-verified in this
        // range (subnormals like MIN_VALUE diverge: Rust 1E-45 vs Java
        // 1.4E-45 - labeled gap).
        let s = format!("{:e}", v); // e.g. "1.5e-4"
        let (mantissa, exp) = s.split_once('e').expect("sci form");
        let (mantissa, neg) = match mantissa.strip_prefix('-') {
            Some(m) => (m, "-"),
            None => (mantissa, ""),
        };
        let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
        let frac = if digits.len() > 1 { &digits[1..] } else { "0" };
        out.push_str(&format!("{}{}.{}E{}", neg, &digits[..1], frac, exp));
    } else {
        out.push_str(&java_float_to_string(v));
    }
    Ok(())
}

struct W {
    buf: String,
}

impl W {
    fn indent(&mut self, level: usize) {
        for _ in 0..level * 2 {
            self.buf.push(' ');
        }
    }
    fn key(&mut self, level: usize, first: bool, key: &str) {
        if !first {
            self.buf.push_str(",\n");
        }
        self.indent(level);
        aosp_quote(&mut self.buf, key);
        self.buf.push_str(": ");
    }
    fn str_field(&mut self, level: usize, first: bool, key: &str, value: &str) {
        self.key(level, first, key);
        aosp_quote(&mut self.buf, value);
    }
    fn int_field(&mut self, level: usize, first: bool, key: &str, value: i64) {
        self.key(level, first, key);
        self.buf.push_str(&value.to_string());
    }
    fn bool_field(&mut self, level: usize, first: bool, key: &str, value: bool) {
        self.key(level, first, key);
        self.buf.push_str(if value { "true" } else { "false" });
    }
    fn float_field(&mut self, level: usize, first: bool, key: &str, value: f32) -> Result<(), ExportError> {
        self.key(level, first, key);
        float_number_to_string(&mut self.buf, value)
    }
    fn open_object(&mut self) {
        self.buf.push('{');
    }
    /// Close a non-empty object at `level`.
    fn close_object(&mut self, level: usize) {
        self.buf.push('\n');
        self.indent(level);
        self.buf.push('}');
    }
}

fn write_settings(w: &mut W, level: usize, s: &ExportSettings) {
    w.open_object();
    w.buf.push('\n');
    w.str_field(level + 1, true, "selected_goals", &s.selected_goals_csv);
    w.str_field(level + 1, false, "last_period_start_date", &s.last_period_start_date);
    w.int_field(level + 1, false, "average_cycle_length_days", s.average_cycle_length as i64);
    w.int_field(level + 1, false, "period_duration_days", s.period_duration as i64);
    w.bool_field(level + 1, false, "remind_period", s.remind_period);
    w.bool_field(level + 1, false, "remind_fertile_window", s.remind_fertile);
    w.bool_field(level + 1, false, "remind_daily_log", s.remind_daily_log);
    w.bool_field(level + 1, false, "private_lock_screen_notifications", s.is_discreet_notification_mode);
    w.close_object(level);
}

fn write_profile(w: &mut W, level: usize, p: Option<&ExportProfile>) {
    let Some(p) = p else {
        w.buf.push_str("{}");
        return;
    };
    w.open_object();
    w.buf.push('\n');
    w.str_field(level + 1, true, "selected_goals", &p.selected_goals_csv);
    w.str_field(level + 1, false, "last_period_date", &p.last_period_date_string);
    w.str_field(level + 1, false, "last_period_end_date", &p.last_period_end_date_string);
    w.str_field(level + 1, false, "period_date_precision", &p.approximate_period_choice);
    w.str_field(level + 1, false, "cycle_length_choice", &p.cycle_length_option);
    w.int_field(level + 1, false, "cycle_length_days", p.cycle_length_days as i64);
    w.int_field(level + 1, false, "period_duration_days", p.period_duration_days as i64);
    w.bool_field(level + 1, false, "fertility_reminders_enabled", p.remind_fertile_window);
    w.str_field(level + 1, false, "typical_symptoms", &p.typical_symptoms_csv);
    w.str_field(level + 1, false, "typical_energy", &p.typical_energy);
    w.str_field(level + 1, false, "typical_sleep_quality", &p.typical_sleep_quality);
    w.close_object(level);
}

fn write_log(w: &mut W, level: usize, l: &ExportLog) -> Result<(), ExportError> {
    w.open_object();
    w.buf.push('\n');
    w.str_field(level + 1, true, "date", &l.date_string);
    w.str_field(level + 1, false, "flow", &l.flow_level);
    w.str_field(level + 1, false, "energy", &l.energy_level);
    w.str_field(level + 1, false, "mood", &l.mood_level);
    w.str_field(level + 1, false, "symptoms", &l.symptoms_csv);
    w.str_field(level + 1, false, "vaginal_health", &l.vaginal_health_csv);
    w.str_field(level + 1, false, "urinary_health", &l.urinary_health_csv);
    w.str_field(level + 1, false, "cervical_mucus", &l.cervical_mucus);
    w.str_field(level + 1, false, "ovulation_test", &l.ovulation_test);
    w.float_field(level + 1, false, "basal_body_temperature_celsius", l.bbt_celsius)?;
    w.float_field(level + 1, false, "sleep_hours", l.sleep_hours)?;
    w.str_field(level + 1, false, "sleep_quality", &l.sleep_quality);
    w.int_field(level + 1, false, "water_glasses", l.water_glasses as i64);
    w.str_field(level + 1, false, "notes", &l.notes);
    w.int_field(level + 1, false, "pain_intensity", l.pain_intensity as i64);
    w.close_object(level);
    Ok(())
}

/// :1988-2046 composed. `epoch_ms` is the injected
/// System.currentTimeMillis(); `logs` is logsMap.value.values (injected);
/// logs are sorted by dateString with Kotlin's STABLE sortedBy.
pub fn build_data_export_json(
    settings: &ExportSettings,
    profile: Option<&ExportProfile>,
    logs: &[ExportLog],
    epoch_ms: i64,
) -> Result<String, ExportError> {
    let mut sorted: Vec<&ExportLog> = logs.iter().collect();
    sorted.sort_by(|a, b| a.date_string.cmp(&b.date_string)); // stable
    let mut w = W { buf: String::new() };
    w.open_object();
    w.buf.push('\n');
    w.str_field(1, true, "format", "CycleCare personal data export");
    w.int_field(1, false, "format_version", 1);
    w.int_field(1, false, "exported_at_epoch_ms", epoch_ms);
    w.key(1, false, "cycle_settings");
    write_settings(&mut w, 1, settings);
    w.key(1, false, "onboarding_profile");
    write_profile(&mut w, 1, profile);
    w.key(1, false, "daily_logs");
    if sorted.is_empty() {
        w.buf.push_str("[]");
    } else {
        w.buf.push_str("[\n");
        for (i, log) in sorted.iter().enumerate() {
            if i > 0 {
                w.buf.push_str(",\n");
            }
            w.indent(2);
            write_log(&mut w, 2, log)?;
        }
        w.buf.push('\n');
        w.indent(1);
        w.buf.push(']');
    }
    w.close_object(0);
    Ok(w.buf)
}
