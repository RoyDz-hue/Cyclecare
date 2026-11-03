//! Port of util/DataExportPdf.kt (main@31b88f5, 141 lines) - LAYOUT
//! DECISION CORE over an injected canvas/pager seam. PdfDocument, Paint,
//! Typeface, the real PDF bytes, SimpleDateFormat and the device clock are
//! host glue (the exported-at timestamp string is injected). Nothing here
//! proves a valid PDF on device; it proves the text stream, positions,
//! wrap and page-break decisions.
//!
//! Kotlin String ops are UTF-16: length/substring/lastIndexOf operate on
//! UTF-16 code units, and lastIndexOf(' ', 88) checks index 88 INCLUSIVE
//! (java.lang.String.lastIndexOf(int, int)).

use crate::kotlin_text::kotlin_is_blank;
use crate::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};

pub const PAGE_WIDTH: i32 = 595; // A4 at 72dpi (:21)
pub const PAGE_HEIGHT: i32 = 842; // :22
pub const MARGIN: f32 = 48.0; // :23
pub const LINE_HEIGHT: f32 = 16.0; // :24
pub const MAX_LINE_CHARS: usize = 88; // :25 - UTF-16 units

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaintKind {
    Title,  // :36-39 bold 20f
    Section, // :40-43 bold 13f
    Body,   // :44 11f
}

/// Injected drawing surface: drawText + the page lifecycle.
pub trait PdfSurface {
    fn draw_text(&mut self, text: &str, x: f32, y: f32, paint: PaintKind);
    /// document.finishPage(page); startPage(++pageNumber); y = MARGIN.
    fn start_new_page(&mut self);
}

/// UTF-16 unit count (Kotlin String.length).
fn u16len(s: &str) -> usize {
    s.encode_utf16().count()
}
/// java.lang.String.lastIndexOf(' ', 88): highest index <= 88 (inclusive)
/// holding ' ', or None. UTF-16 indices.
fn u16_last_index_of_space(units: &[u16], from_index: usize) -> Option<usize> {
    let start = from_index.min(units.len().saturating_sub(1));
    (0..=start).rev().find(|&i| units[i] == b' ' as u16)
}

/// Kotlin trimStart() in UTF-16 units: skip leading code points where
/// Char.isWhitespace holds.
fn utf16_trim_start(units: &[u16]) -> &[u16] {
    let mut i = 0;
    while i < units.len() {
        let (c, len) = if (0xD800..0xDC00).contains(&units[i]) && i + 1 < units.len() {
            let cp = 0x10000
                + (((units[i] as u32) - 0xD800) << 10)
                + (units[i + 1] as u32 - 0xDC00);
            (char::from_u32(cp).unwrap_or('\u{FFFD}'), 2)
        } else {
            (char::from_u32(units[i] as u32).unwrap_or('\u{FFFD}'), 1)
        };
        if !crate::kotlin_text::kotlin_is_whitespace(c) {
            break;
        }
        i += len;
    }
    &units[i..]
}

/// The layout cursor: which page-line we are on, in points from the top.
pub struct Layout<'a, S: PdfSurface> {
    surface: &'a mut S,
    y: f32,
}

impl<'a, S: PdfSurface> Layout<'a, S> {
    pub fn new(surface: &'a mut S) -> Self {
        Layout { surface, y: MARGIN }
    }

    /// :56-72 line(): naive word wrap with page-break-before-chunk. Runs at
    /// least once even for an empty string (do-while). Operates in UTF-16
    /// units end-to-end: a hard cut at 88 can split a surrogate pair
    /// exactly as Kotlin substring does (the lone unit reaches drawText;
    /// here it decodes lossy - a rendering-only deviation, while the
    /// removal/rest computation stays unit-exact like removePrefix).
    pub fn line(&mut self, text: &str, paint: PaintKind, gap: f32) {
        let mut remaining: Vec<u16> = text.encode_utf16().collect();
        let mut first = true;
        loop {
            // :60
            if self.y > PAGE_HEIGHT as f32 - MARGIN {
                self.surface.start_new_page();
                self.y = MARGIN;
            }
            // :61-66
            let chunk: Vec<u16> = if remaining.len() <= MAX_LINE_CHARS {
                remaining.clone()
            } else {
                let cut = u16_last_index_of_space(&remaining, MAX_LINE_CHARS)
                    .filter(|&i| i > 0)
                    .unwrap_or(MAX_LINE_CHARS);
                remaining[..cut].to_vec()
            };
            // :67
            let chunk_str = String::from_utf16_lossy(&chunk);
            let drawn = if first { chunk_str.clone() } else { format!("    {chunk_str}") };
            self.surface.draw_text(&drawn, MARGIN, self.y, paint);
            // :68
            self.y += gap;
            // :69 removePrefix(chunk).trimStart() - unit-exact.
            remaining = utf16_trim_start(&remaining[chunk.len()..]).to_vec();
            first = false;
            // :71
            if remaining.is_empty() {
                break;
            }
        }
    }

    /// :74-77
    pub fn field(&mut self, label: &str, value: Option<&str>) {
        let display = match value {
            Some(v) if !kotlin_is_blank(v) => v,
            _ => "—",
        };
        self.line(&format!("{label}: {display}"), PaintKind::Body, LINE_HEIGHT);
    }

    /// :79
    pub fn bool_field(&mut self, label: &str, value: bool) {
        self.field(label, Some(if value { "Yes" } else { "No" }));
    }

    /// Per-log pre-break: :117 y > PAGE_HEIGHT - MARGIN - LINE_HEIGHT*6.
    pub fn ensure_room_for_log(&mut self) {
        if self.y > PAGE_HEIGHT as f32 - MARGIN - LINE_HEIGHT * 6.0 {
            self.surface.start_new_page();
            self.y = MARGIN;
        }
    }
}

/// Java Float.toString parity for the PLAIN decimal range [1e-3, 1e7):
/// shortest round-trip digits (Rust's {} already produces those for f32)
/// with Java's mandatory ".0" on integral values. Scientific-notation
/// magnitudes are NOT covered (labeled; body-temperature/sleep values this
/// prints live in the plain range).
pub fn java_float_to_string(v: f32) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    let a = v.abs();
    if a != 0.0 && !(1e-3..1e7).contains(&a) {
        // Outside the verified plain range: still emit Rust shortest form,
        // flagged here as not Java-verified for sci-notation magnitudes.
        return format!("{v}");
    }
    let s = format!("{v}");
    if s.contains('.') || s.contains('e') || s.contains('E') {
        s
    } else {
        format!("{s}.0")
    }
}

/// :81-139 build(): the full section structure. `exported_at` is the
/// pre-formatted SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
/// .format(Date()) string - clock/locale are host glue (:86).
pub fn build<S: PdfSurface>(
    surface: &mut S,
    settings: &CycleSettings,
    profile: Option<&OnboardingProfile>,
    logs: &[CycleDayLog],
    exported_at: &str,
) {
    let mut l = Layout::new(surface);
    // :83-87 header
    l.line("CycleCare - My Data Export", PaintKind::Title, 30.0);
    l.line(&format!("Exported: {exported_at}"), PaintKind::Body, 28.0);
    // :89-99 cycle settings
    l.line("Cycle settings", PaintKind::Section, 22.0);
    l.field("Goals", Some(&settings.selected_goals_csv));
    l.field("Last period start", Some(&settings.last_period_start_date));
    l.field("Average cycle length", Some(&format!("{} days", settings.average_cycle_length)));
    l.field("Period duration", Some(&format!("{} days", settings.period_duration)));
    l.bool_field("Period reminders", settings.remind_period);
    l.bool_field("Fertile window reminders", settings.remind_fertile);
    l.bool_field("Daily log reminders", settings.remind_daily_log);
    l.line("", PaintKind::Body, LINE_HEIGHT);
    // :101-116 profile baseline
    if let Some(p) = profile {
        l.line("Profile baseline", PaintKind::Section, 22.0);
        l.field("Goals", Some(&p.selected_goals_csv));
        l.field("Last period", Some(&p.last_period_date_string));
        if !kotlin_is_blank(&p.last_period_end_date_string) {
            l.field("Period end", Some(&p.last_period_end_date_string));
        }
        l.field("Period date precision", Some(&p.approximate_period_choice));
        let cycle_len = if kotlin_is_blank(&p.cycle_length_option) {
            format!("{} days", p.cycle_length_days)
        } else {
            p.cycle_length_option.clone()
        };
        l.field("Cycle length", Some(&cycle_len));
        l.field("Period duration", Some(&format!("{} days", p.period_duration_days)));
        l.bool_field("Fertile window reminders", p.remind_fertile_window);
        l.field("Typical symptoms", Some(&p.typical_symptoms_csv));
        l.field("Typical energy", Some(&p.typical_energy));
        l.field("Typical sleep", Some(&p.typical_sleep_quality));
        l.line("", PaintKind::Body, LINE_HEIGHT);
    }
    // :118-137 daily logs
    l.line(&format!("Daily logs ({} entries)", logs.len()), PaintKind::Section, 22.0);
    for log in logs {
        l.ensure_room_for_log(); // :117
        l.line(&log.date, PaintKind::Section, 18.0);
        if !kotlin_is_blank(&log.flow) { l.field("Flow", Some(&log.flow)); }
        if !kotlin_is_blank(&log.energy) { l.field("Energy", Some(&log.energy)); }
        if !kotlin_is_blank(&log.mood) { l.field("Mood", Some(&log.mood)); }
        if !kotlin_is_blank(&log.symptoms) { l.field("Symptoms", Some(&log.symptoms)); }
        if !kotlin_is_blank(&log.vaginal_health) { l.field("Vaginal health", Some(&log.vaginal_health)); }
        if !kotlin_is_blank(&log.urinary_health) { l.field("Urinary health", Some(&log.urinary_health)); }
        if !kotlin_is_blank(&log.cervical_mucus) { l.field("Cervical mucus", Some(&log.cervical_mucus)); }
        if !kotlin_is_blank(&log.ovulation_test) && log.ovulation_test != "NONE" {
            l.field("Ovulation test", Some(&log.ovulation_test));
        }
        if log.bbt_celsius > 0.0 {
            l.field("Basal body temperature", Some(&format!("{} °C", java_float_to_string(log.bbt_celsius))));
        }
        if log.sleep_hours > 0.0 {
            let qual = if !kotlin_is_blank(&log.sleep_quality) {
                format!(" ({})", log.sleep_quality)
            } else {
                String::new()
            };
            l.field("Sleep", Some(&format!("{} h{}", java_float_to_string(log.sleep_hours), qual)));
        }
        if log.water_glasses > 0 {
            l.field("Water", Some(&format!("{} glasses", log.water_glasses)));
        }
        if log.pain_intensity > 0 {
            l.field("Pain intensity", Some(&format!("{}/10", log.pain_intensity)));
        }
        if !kotlin_is_blank(&log.notes) { l.field("Notes", Some(&log.notes)); }
        l.line("", PaintKind::Body, LINE_HEIGHT);
    }
}
