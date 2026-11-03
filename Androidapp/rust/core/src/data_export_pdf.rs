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

/// Java Float.toString parity for the PLAIN decimal range [1e-3, 1e7),
/// differential-verified against jdk-17.0.21+9 Float.toString over 41,743
/// values (integers, 0.01/0.25/0.001 grids and ~25k random bit patterns):
/// Rust's shortest-round-trip digits are used, then JAVA'S TIE RULE is
/// applied exactly: when the two length-n neighbours of the shortest form
/// are EXACTLY EQUIDISTANT from the true f32 value, Java picks the one
/// whose last digit is even; Rust/Ryu picks differently in 371/41,743
/// cases. The comparison is exact: the f32's full decimal expansion is
/// computed with integer digit arithmetic (mantissa * 5^k), no floats and
/// no epsilons. Scientific-notation magnitudes stay uncovered/labeled.
pub fn java_float_to_string(v: f32) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    let a = v.abs();
    if a != 0.0 && !(1e-3..1e7).contains(&a) {
        // Outside the verified plain range: Rust shortest form, NOT
        // Java-verified for sci-notation magnitudes.
        return format!("{v}");
    }
    let s = format!("{v}");
    if s.contains('e') || s.contains('E') {
        return s; // Rust sci form inside the plain range: not expected; unverified.
    }
    let resolved = resolve_ties_java_rule(v, &s);
    if resolved.contains('.') {
        resolved
    } else {
        format!("{resolved}.0")
    }
}

/// Exact decimal expansion of a finite f32: (digits, exp10) such that
/// value = 0.digits * 10^exp10 with digits having no trailing zeros.
/// mantissa * 2^e: for e>=0 digits = mantissa << e; for e<0 digits =
/// mantissa * 5^-e with the decimal point shifted.
fn f32_exact_decimal(v: f32) -> (Vec<u8>, i32) {
    let bits = v.to_bits();
    let raw_exp = ((bits >> 23) & 0xff) as i32;
    let raw_man = (bits & 0x7fffff) as u64;
    let (mantissa, e2) = if raw_exp == 0 {
        (raw_man, -149)
    } else {
        (raw_man | 0x800000, raw_exp - 150)
    };
    // digits of the integer mantissa (* 2^e2 or * 5^-e2), little-endian base 10.
    let mut digits: Vec<u8> = Vec::new();
    let mut m = mantissa;
    while m > 0 {
        digits.push((m % 10) as u8);
        m /= 10;
    }
    if digits.is_empty() {
        digits.push(0);
    }
    let mut exp10 = 0i32; // value = digits * 10^exp10 * (2^e2 handling below)
    if e2 >= 0 {
        for _ in 0..e2 {
            // digits *= 2
            let mut carry = 0u32;
            for d in digits.iter_mut() {
                let cur = *d as u32 * 2 + carry;
                *d = (cur % 10) as u8;
                carry = cur / 10;
            }
            while carry > 0 {
                digits.push((carry % 10) as u8);
                carry /= 10;
            }
        }
    } else {
        for _ in 0..(-e2) {
            // digits *= 5, exp10 -= 1  (x * 2^-1 = x * 5 * 10^-1)
            let mut carry = 0u32;
            for d in digits.iter_mut() {
                let cur = *d as u32 * 5 + carry;
                *d = (cur % 10) as u8;
                carry = cur / 10;
            }
            while carry > 0 {
                digits.push((carry % 10) as u8);
                carry /= 10;
            }
            exp10 -= 1;
        }
    }
    while digits.len() > 1 && digits[0] == 0 {
        digits.remove(0); // removes a trailing zero of the integer: value/10
        exp10 += 1; // so the scale must multiply by 10 to compensate
    }
    (digits, exp10) // value = (digits as integer) * 10^exp10
}

/// Compare candidate decimal strings at the shortest length and apply
/// Java's closest-then-even rule. `s` is Rust's shortest plain form.
fn resolve_ties_java_rule(v: f32, s: &str) -> String {
    let neg = s.starts_with('-');
    let body = if neg { &s[1..] } else { s };
    let (int_part, frac_part) = match body.split_once('.') {
        Some((i, f)) => (i.to_string(), f.to_string()),
        None => (body.to_string(), String::new()),
    };
    let digits: Vec<u8> = int_part.bytes().chain(frac_part.bytes()).map(|b| b - b'0').collect();
    let n_frac = frac_part.len() as i32;
    // Neighbours at the same digit count: digits +/- 1 in the last place.
    let bump = |ds: &Vec<u8>, delta: i32| -> Option<(Vec<u8>, i32)> {
        let mut d = ds.clone();
        let mut exp_shift = 0i32;
        let mut idx = d.len() as i32 - 1;
        let mut delta = delta;
        while delta != 0 && idx >= 0 {
            let cur = d[idx as usize] as i32 + delta;
            if cur > 9 {
                d[idx as usize] = (cur - 10) as u8;
                delta = 1;
                idx -= 1;
            } else if cur < 0 {
                d[idx as usize] = (cur + 10) as u8;
                delta = -1;
                idx -= 1;
            } else {
                d[idx as usize] = cur as u8;
                delta = 0;
            }
        }
        if delta > 0 {
            d.insert(0, 1);
            exp_shift = 1; // carry grew the integer part
        }
        Some((d, exp_shift))
    };
    let lo = bump(&digits, -1);
    let hi = bump(&digits, 1);
    let (lo, hi) = match (lo, hi) {
        (Some(a), Some(b)) => (a, b),
        _ => return s.to_string(),
    };
    // Exact decimal machinery: |v - cand| at a common scale, no floats.
    let (vd, vexp) = f32_exact_decimal(v.abs());
    let to_be = |d: &[u8], extra_zeros: i32| -> Vec<u8> {
        let mut be: Vec<u8> = d.iter().rev().cloned().collect();
        for _ in 0..extra_zeros.max(0) {
            be.push(0);
        }
        if be.is_empty() {
            be.push(0);
        }
        be
    };
    let e_common = |cexp: i32| vexp.min(cexp);
    let scaled = |cexp: i32, is_v: bool, cd: &[u8]| -> Vec<u8> {
        let e = e_common(cexp);
        if is_v {
            to_be(&vd, vexp - e) // vd is little-endian
        } else {
            // candidate digits are already big-endian: append scale zeros
            let mut be: Vec<u8> = cd.to_vec();
            for _ in 0..(cexp - e).max(0) {
                be.push(0);
            }
            if be.is_empty() {
                be.push(0);
            }
            be
        }
    };
    let dist = |cand: &(Vec<u8>, i32)| -> Vec<u8> {
        let cexp = -n_frac + cand.1;
        let vbe = scaled(cexp, true, &[]);
        let cbe = scaled(cexp, false, &cand.0);
        if cmp_be(&vbe, &cbe) >= 0 {
            sub_be(&vbe, &cbe)
        } else {
            sub_be(&cbe, &vbe)
        }
    };
    // The original string is itself a candidate and usually the exact or
    // strictly-closest one. Only when the NEIGHBOUR on the far side of v
    // is exactly equidistant does Java's even-last-digit tie rule apply.
    let orig = (digits.clone(), 0i32);
    let sexp = -n_frac;
    let vbe = scaled(sexp, true, &[]);
    let sbe = scaled(sexp, false, &orig.0);
    let ord_vs_s = cmp_be(&vbe, &sbe);
    if ord_vs_s == 0 {
        return s.to_string(); // exactly representable: Rust's form is Java's
    }
    let d_s = if ord_vs_s > 0 { sub_be(&vbe, &sbe) } else { sub_be(&sbe, &vbe) };
    let other = if ord_vs_s > 0 { hi } else { lo };
    let d_o = dist(&other);
    let ord = cmp_be(&d_s, &d_o);
    let chosen = if ord <= 0 {
        if ord == 0 {
            // EXACT tie: Java picks the EVEN last digit.
            let s_last = orig.0[orig.0.len() - 1];
            let o_last = other.0[other.0.len() - 1];
            if o_last % 2 == 0 && s_last % 2 != 0 {
                other
            } else {
                orig
            }
        } else {
            orig
        }
    } else {
        other
    };
    // Re-render the chosen candidate with its decimal point.
    let (cd, cshift) = chosen;
    let int_len = cd.len() as i32 - n_frac + cshift;
    let mut out = String::new();
    if neg {
        out.push('-');
    }
    if int_len <= 0 {
        out.push_str("0.");
        for _ in 0..(-int_len) {
            out.push('0');
        }
        for d in &cd {
            out.push((b'0' + d) as char);
        }
        return out;
    }
    for (i, d) in cd.iter().enumerate() {
        if i as i32 == int_len {
            out.push('.');
        }
        out.push((b'0' + d) as char);
    }
    if int_len as usize > cd.len() {
        for _ in 0..(int_len as usize - cd.len()) {
            out.push('0');
        }
    }
    out
}

fn cmp_be(a: &[u8], b: &[u8]) -> i32 {
    // big-endian digit slices, may have leading zeros
    let na: Vec<u8> = a.iter().skip_while(|&&d| d == 0).cloned().collect();
    let nb: Vec<u8> = b.iter().skip_while(|&&d| d == 0).cloned().collect();
    if na.len() != nb.len() {
        return if na.len() < nb.len() { -1 } else { 1 };
    }
    for i in 0..na.len() {
        if na[i] != nb[i] {
            return if na[i] < nb[i] { -1 } else { 1 };
        }
    }
    0
}

fn sub_be(a_in: &[u8], b_in: &[u8]) -> Vec<u8> {
    // a >= b, big-endian; strip leading zeros first
    let a: &[u8] = {
        let i = a_in.iter().position(|&d| d != 0).unwrap_or(a_in.len().saturating_sub(1));
        &a_in[i..]
    };
    let b: &[u8] = {
        let i = b_in.iter().position(|&d| d != 0).unwrap_or(b_in.len().saturating_sub(1));
        &b_in[i..]
    };
    let mut out = vec![0u8; a.len()];
    let mut borrow = 0i32;
    for i in (0..a.len()).rev() {
        let bi = if i >= a.len() - b.len() { b[i - (a.len() - b.len())] as i32 } else { 0 };
        let mut cur = a[i] as i32 - bi - borrow;
        if cur < 0 {
            cur += 10;
            borrow = 1;
        } else {
            borrow = 0;
        }
        out[i] = cur as u8;
    }
    out
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
