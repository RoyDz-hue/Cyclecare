//! sync_codec.rs — Rust port of HealthSyncPayloadCodec's wire codec from
//! HealthSyncLocal.kt, byte-faithful to the *device* org.json (AOSP libcore
//! android-14.0.0_r1), NOT the Maven org.json artifact.
//!
//! Device-faithful behaviors locked by executed AOSP oracles
//! (fixtures/oracle/results_codec_*.tsv, produced by compiling AOSP libcore
//! org.json sources and running the verbatim Kotlin codec against them):
//! - JSONObject key order is INSERTION order (AOSP LinkedHashMap), not Maven
//!   HashMap order. Payload strings and sha256 hashes differ between the two.
//! - JSONStringer escapes '/' as "\/" in strings (Maven does not).
//! - numberToString: -0.0 -> "-0"; integral doubles strip ".0" (37.0 -> "37",
//!   0.0 -> "0"); non-integral doubles use Java Double.toString formatting.
//! - Float->Double widening is preserved (36.6f -> 36.599998474121094).
//! - AOSP JSONTokener readLiteral: integers parse as Integer/Long; a leading
//!   "0" makes the literal OCTAL ("010" -> 8); "0x" prefixes parse hex;
//!   otherwise Double; unrecognized literals become bare Strings.
//! - hash(null) = sha256("null").

use sha2::{Digest, Sha256};

// ---------- AOSP JSON value model ----------

#[derive(Debug, Clone, PartialEq)]
pub enum JsonValue {
    Null,
    Bool(bool),
    Int(i32),
    Long(i64),
    Double(f64),
    Str(String),
    Array(Vec<JsonValue>),
    /// Insertion-ordered object (AOSP LinkedHashMap semantics).
    Object(Vec<(String, JsonValue)>),
}

impl JsonValue {
    fn is_number(&self) -> bool {
        matches!(self, JsonValue::Int(_) | JsonValue::Long(_) | JsonValue::Double(_))
    }
    fn to_f64(&self) -> f64 {
        match self {
            JsonValue::Int(v) => *v as f64,
            JsonValue::Long(v) => *v as f64,
            JsonValue::Double(v) => *v,
            _ => f64::NAN,
        }
    }
}

// ---------- AOSP JSONStringer serialization ----------

/// AOSP JSONStringer.string: escapes ", \, /, \t, \b, \n, \r, \f and
/// \u00xx for control chars <= 0x1F; every other char is emitted raw.
fn escape_json_string(value: &str, out: &mut String) {
    out.push('"');
    for c in value.chars() {
        match c {
            '"' | '\\' | '/' => {
                out.push('\\');
                out.push(c);
            }
            '\t' => out.push_str("\\t"),
            '\u{08}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{0C}' => out.push_str("\\f"),
            c if (c as u32) <= 0x1F => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Java Double.toString via ryu shortest digits, rendered per the JDK 17
/// spec: plain notation for 10^-3 <= |v| < 10^7, otherwise one digit before
/// the point, an optional fraction, and "E<exp>".
pub fn java_double_to_string(v: f64) -> String {
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0".to_string() } else { "0.0".to_string() };
    }
    let neg = v < 0.0;
    let mut buf = ryu::Buffer::new();
    let s = buf.format_finite(v.abs());
    // Parse ryu output: digits[.digits][e[-]digits]
    let (mantissa, exp10) = match s.find(['e', 'E']) {
        Some(i) => (&s[..i], s[i + 1..].parse::<i32>().unwrap()),
        None => (s, 0),
    };
    let point_pos = mantissa.find('.').unwrap_or(mantissa.len()) as i32;
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    let digits = digits.trim_start_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    let trimmed_leading = (mantissa.chars().filter(|c| *c != '.').count() - digits.len()) as i32;
    // ryu's format_finite can emit expanded forms (e.g. 1e7 as "10000000.0");
    // Java Double.toString uses the shortest digit string, so trailing zeros
    // in the significand are dropped (they do not move the decimal exponent,
    // which is fixed by trimmed_leading above).
    let digits = digits.trim_end_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    // value = 0.digits * 10^sci_exp+1 ; s = scientific exponent
    let sci = point_pos - trimmed_leading + exp10 - 1;
    let d = digits.as_bytes();
    let n = d.len() as i32;
    let mut out = String::new();
    if neg {
        out.push('-');
    }
    if (-3..7).contains(&sci) {
        if sci >= 0 {
            let ip = (sci + 1) as usize;
            for i in 0..ip {
                if (i as i32) < n {
                    out.push(d[i] as char);
                } else {
                    out.push('0');
                }
            }
            if n > sci + 1 {
                out.push('.');
                for i in (sci + 1) as usize..d.len() {
                    out.push(d[i] as char);
                }
            } else {
                out.push_str(".0");
            }
        } else {
            out.push_str("0.");
            for _ in 0..(-sci - 1) {
                out.push('0');
            }
            for &b in d {
                out.push(b as char);
            }
        }
    } else {
        out.push(d[0] as char);
        out.push('.');
        if d.len() > 1 {
            for &b in &d[1..] {
                out.push(b as char);
            }
        } else {
            out.push('0');
        }
        out.push('E');
        out.push_str(&sci.to_string());
    }
    out
}

/// AOSP JSONObject.numberToString(Number).
fn number_to_string(v: &JsonValue) -> String {
    let double_value = v.to_f64();
    assert!(
        double_value.is_finite(),
        "Forbidden numeric value: NaN/Infinity not allowed"
    );
    if matches!(v, JsonValue::Double(d) if *d == 0.0 && d.is_sign_negative()) {
        return "-0".to_string();
    }
    let long_value = double_value as i64; // Java narrows with saturation, same as Rust `as`
    if double_value == long_value as f64 {
        return long_value.to_string();
    }
    match v {
        JsonValue::Double(d) => java_double_to_string(*d),
        JsonValue::Int(i) => i.to_string(),
        JsonValue::Long(l) => l.to_string(),
        _ => unreachable!(),
    }
}

fn write_value(v: &JsonValue, out: &mut String) {
    match v {
        JsonValue::Null => out.push_str("null"),
        JsonValue::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        JsonValue::Int(_) | JsonValue::Long(_) | JsonValue::Double(_) => {
            out.push_str(&number_to_string(v))
        }
        JsonValue::Str(s) => escape_json_string(s, out),
        JsonValue::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_value(item, out);
            }
            out.push(']');
        }
        JsonValue::Object(pairs) => {
            out.push('{');
            for (i, (k, val)) in pairs.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                escape_json_string(k, out);
                out.push(':');
                write_value(val, out);
            }
            out.push('}');
        }
    }
}

// ---------- AOSP JSONTokener parsing ----------

pub struct JsonParser<'a> {
    chars: Vec<char>,
    pos: usize,
    _src: &'a str,
}

impl<'a> JsonParser<'a> {
    pub fn new(src: &'a str) -> Self {
        JsonParser { chars: src.chars().collect(), pos: 0, _src: src }
    }

    pub fn parse(&mut self) -> Result<JsonValue, String> {
        let v = self.next_value()?;
        Ok(v)
    }

    fn syntax_error<T>(&self, msg: &str) -> Result<T, String> {
        Err(format!("{} at character {}", msg, self.pos))
    }

    /// nextCleanInternal: skips whitespace and AOSP's three comment forms
    /// (// line, /* block, # line), returning the next significant char.
    fn next_clean(&mut self) -> Result<Option<char>, String> {
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            self.pos += 1;
            match c {
                '\t' | ' ' | '\n' | '\r' => continue,
                '/' => {
                    if self.pos == self.chars.len() {
                        return Ok(Some('/'));
                    }
                    match self.chars[self.pos] {
                        '*' => {
                            self.pos += 1;
                            loop {
                                if self.pos >= self.chars.len() {
                                    return self.syntax_error("Unterminated comment");
                                }
                                if self.chars[self.pos] == '*'
                                    && self.pos + 1 < self.chars.len()
                                    && self.chars[self.pos + 1] == '/'
                                {
                                    self.pos += 2;
                                    break;
                                }
                                self.pos += 1;
                            }
                        }
                        '/' => {
                            self.pos += 1;
                            while self.pos < self.chars.len()
                                && self.chars[self.pos] != '\n'
                                && self.chars[self.pos] != '\r'
                            {
                                self.pos += 1;
                            }
                        }
                        _ => return Ok(Some('/')),
                    }
                }
                '#' => {
                    while self.pos < self.chars.len()
                        && self.chars[self.pos] != '\n'
                        && self.chars[self.pos] != '\r'
                    {
                        self.pos += 1;
                    }
                }
                c => return Ok(Some(c)),
            }
        }
        Ok(None)
    }

    fn next_value(&mut self) -> Result<JsonValue, String> {
        match self.next_clean()? {
            None => self.syntax_error("End of input"),
            Some('{') => self.read_object(),
            Some('[') => self.read_array(),
            Some(q @ ('\'' | '"')) => Ok(JsonValue::Str(self.next_string(q)?)),
            Some(c) => {
                self.pos -= 1;
                let _ = c;
                self.read_literal()
            }
        }
    }

    fn read_object(&mut self) -> Result<JsonValue, String> {
        let mut pairs: Vec<(String, JsonValue)> = Vec::new();
        let mut first = true;
        loop {
            let c = self.next_clean()?;
            match c {
                None => return self.syntax_error("Unterminated object"),
                Some('}') => {
                    return if first {
                        Ok(JsonValue::Object(pairs))
                    } else {
                        self.syntax_error("Trailing comma in object")
                    }
                }
                Some(q @ ('\'' | '"')) => {
                    let key = self.next_string(q)?;
                    self.read_pair(&mut pairs, key)?;
                    first = false;
                }
                Some(_) => {
                    self.pos -= 1;
                    // AOSP allows unquoted literal keys.
                    let key = match self.read_literal()? {
                        JsonValue::Str(s) => s,
                        JsonValue::Int(i) => i.to_string(),
                        JsonValue::Long(l) => l.to_string(),
                        JsonValue::Double(dv) => java_double_to_string(dv),
                        _ => return self.syntax_error("Expected string key"),
                    };
                    self.read_pair(&mut pairs, key)?;
                    first = false;
                }
            }
            match self.next_clean()? {
                Some(',') | Some(';') => continue,
                Some('}') => return Ok(JsonValue::Object(pairs)),
                _ => return self.syntax_error("Unterminated object"),
            }
        }
    }

    fn read_pair(&mut self, pairs: &mut Vec<(String, JsonValue)>, key: String) -> Result<(), String> {
        match self.next_clean()? {
            Some(':') => {}
            Some('=') => {
                // AOSP tolerates "=>" after '='
                if self.pos < self.chars.len() && self.chars[self.pos] == '>' {
                    self.pos += 1;
                }
            }
            _ => return self.syntax_error("Expected ':' after object key"),
        }
        let value = self.next_value()?;
        // LinkedHashMap.put: existing key keeps position, value replaced.
        if let Some(slot) = pairs.iter_mut().find(|(k, _)| *k == key) {
            slot.1 = value;
        } else {
            pairs.push((key, value));
        }
        Ok(())
    }

    fn read_array(&mut self) -> Result<JsonValue, String> {
        let mut items = Vec::new();
        match self.next_clean()? {
            Some(']') => return Ok(JsonValue::Array(items)),
            Some(_) => self.pos -= 1,
            None => return self.syntax_error("Unterminated array"),
        }
        loop {
            items.push(self.next_value()?);
            match self.next_clean()? {
                Some(',') | Some(';') => {
                    if let Some(']') = self.next_clean()? {
                        return Ok(JsonValue::Array(items));
                    }
                    self.pos -= 1;
                }
                Some(']') => return Ok(JsonValue::Array(items)),
                _ => return self.syntax_error("Unterminated array"),
            }
        }
    }

    fn next_string(&mut self, quote: char) -> Result<String, String> {
        let mut out = String::new();
        loop {
            if self.pos >= self.chars.len() {
                return self.syntax_error("Unterminated string");
            }
            let c = self.chars[self.pos];
            self.pos += 1;
            match c {
                '\\' => {
                    if self.pos >= self.chars.len() {
                        return self.syntax_error("Unterminated escape sequence");
                    }
                    let e = self.chars[self.pos];
                    self.pos += 1;
                    match e {
                        'b' => out.push('\u{08}'),
                        'f' => out.push('\u{0C}'),
                        'n' => out.push('\n'),
                        'r' => out.push('\r'),
                        't' => out.push('\t'),
                        'u' => {
                            if self.pos + 4 > self.chars.len() {
                                return self.syntax_error("Unterminated escape sequence");
                            }
                            let hex: String =
                                self.chars[self.pos..self.pos + 4].iter().collect();
                            let cp = u32::from_str_radix(&hex, 16)
                                .map_err(|_| "Invalid unicode escape".to_string())?;
                            self.pos += 4;
                            out.push(char::from_u32(cp).unwrap_or('\u{FFFD}'));
                        }
                        // Covers \/, \\, \", \' and any other escaped char.
                        other => out.push(other),
                    }
                }
                c if c == quote => return Ok(out),
                '\n' | '\r' => return self.syntax_error("Unterminated string"),
                c => out.push(c),
            }
        }
    }

    fn read_literal(&mut self) -> Result<JsonValue, String> {
        // nextToInternal("{}[]/\\:,=;# \t\f")
        const EXCLUDED: &[char] = &[
            '{', '}', '[', ']', '/', '\\', ':', ',', '=', ';', '#', ' ', '\t', '\u{0C}',
        ];
        let start = self.pos;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if EXCLUDED.contains(&c) || c == '\n' || c == '\r' {
                break;
            }
            self.pos += 1;
        }
        let literal: String = self.chars[start..self.pos].iter().collect();
        if literal.is_empty() {
            return self.syntax_error("Expected literal value");
        }
        if literal.eq_ignore_ascii_case("null") {
            return Ok(JsonValue::Null);
        }
        if literal.eq_ignore_ascii_case("true") {
            return Ok(JsonValue::Bool(true));
        }
        if literal.eq_ignore_ascii_case("false") {
            return Ok(JsonValue::Bool(false));
        }
        if !literal.contains('.') {
            let (number, base) = if literal.starts_with("0x") || literal.starts_with("0X") {
                (&literal[2..], 16)
            } else if literal.starts_with('0') && literal.len() > 1 {
                (&literal[1..], 8)
            } else {
                (literal.as_str(), 10)
            };
            if let Ok(long_value) = i64::from_str_radix(number, base) {
                if long_value <= i32::MAX as i64 && long_value >= i32::MIN as i64 {
                    return Ok(JsonValue::Int(long_value as i32));
                }
                return Ok(JsonValue::Long(long_value));
            }
            // Fall through: integral overflow, exponent form, or unquoted string.
        }
        if let Ok(d) = literal.parse::<f64>() {
            return Ok(JsonValue::Double(d));
        }
        Ok(JsonValue::Str(literal))
    }
}

// ---------- Domain models (from HealthSyncLocal.kt contracts) ----------

#[derive(Debug, Clone, PartialEq)]
pub struct CycleDayLog {
    pub date: String,
    pub flow: String,
    pub energy: String,
    pub mood: String,
    pub symptoms: String,
    pub vaginal_health: String,
    pub urinary_health: String,
    pub cervical_mucus: String,
    pub ovulation_test: String,
    pub bbt_celsius: f32,
    pub sleep_hours: f32,
    pub sleep_quality: String,
    pub water_glasses: i32,
    pub notes: String,
    pub pain_intensity: i32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CycleSettings {
    pub selected_goals_csv: String,
    pub last_period_start_date: String,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub luteal_phase_days: i32,
    pub is_pregnancy_mode: bool,
    pub pregnancy_due_date: String,
}

/// Security-critical: restored profiles can never certify age, terms,
/// onboarding completion, or account mode (port of the Kotlin reset).
#[derive(Debug, Clone, PartialEq)]
pub struct OnboardingProfile {
    pub selected_goals_csv: String,
    pub last_period_date_string: String,
    pub last_period_end_date_string: String,
    pub approximate_period_choice: String,
    pub cycle_length_option: String,
    pub cycle_length_days: i32,
    pub period_duration_days: i32,
    pub typical_symptoms_csv: String,
    pub typical_energy: String,
    pub typical_sleep_quality: String,
    pub has_completed_deep_baseline: bool,
    pub is_completed: bool,
    pub completed_at_timestamp: i64,
    pub is_age_verified: bool,
    pub is_terms_agreed: bool,
}

impl Default for OnboardingProfile {
    fn default() -> Self {
        OnboardingProfile {
            selected_goals_csv: String::new(),
            last_period_date_string: String::new(),
            last_period_end_date_string: String::new(),
            approximate_period_choice: String::new(),
            cycle_length_option: String::new(),
            cycle_length_days: 28,
            period_duration_days: 5,
            typical_symptoms_csv: String::new(),
            typical_energy: String::new(),
            typical_sleep_quality: String::new(),
            has_completed_deep_baseline: false,
            is_completed: false,
            completed_at_timestamp: 0,
            is_age_verified: false,
            is_terms_agreed: false,
        }
    }
}

impl Default for CycleSettings {
    fn default() -> Self {
        CycleSettings {
            selected_goals_csv: String::new(),
            last_period_start_date: String::new(),
            average_cycle_length: 28,
            period_duration: 5,
            luteal_phase_days: 14,
            is_pregnancy_mode: false,
            pregnancy_due_date: String::new(),
        }
    }
}

// ---------- Payload serialization (verbatim key order from Kotlin) ----------

pub fn log_payload(log: &CycleDayLog) -> String {
    let pairs: Vec<(String, JsonValue)> = vec![
        ("date".into(), JsonValue::Str(log.date.clone())),
        ("flow".into(), JsonValue::Str(log.flow.clone())),
        ("energy".into(), JsonValue::Str(log.energy.clone())),
        ("mood".into(), JsonValue::Str(log.mood.clone())),
        ("symptoms".into(), JsonValue::Str(log.symptoms.clone())),
        ("vaginal_health".into(), JsonValue::Str(log.vaginal_health.clone())),
        ("urinary_health".into(), JsonValue::Str(log.urinary_health.clone())),
        ("cervical_mucus".into(), JsonValue::Str(log.cervical_mucus.clone())),
        ("ovulation_test".into(), JsonValue::Str(log.ovulation_test.clone())),
        ("bbt_celsius".into(), JsonValue::Double(log.bbt_celsius as f64)),
        ("sleep_hours".into(), JsonValue::Double(log.sleep_hours as f64)),
        ("sleep_quality".into(), JsonValue::Str(log.sleep_quality.clone())),
        ("water_glasses".into(), JsonValue::Int(log.water_glasses)),
        ("notes".into(), JsonValue::Str(log.notes.clone())),
        ("pain_intensity".into(), JsonValue::Int(log.pain_intensity)),
    ];
    let mut out = String::new();
    write_value(&JsonValue::Object(pairs), &mut out);
    out
}

pub fn settings_payload(value: &CycleSettings) -> String {
    let pairs: Vec<(String, JsonValue)> = vec![
        ("selected_goals".into(), JsonValue::Str(value.selected_goals_csv.clone())),
        ("last_period_start_date".into(), JsonValue::Str(value.last_period_start_date.clone())),
        ("average_cycle_length".into(), JsonValue::Int(value.average_cycle_length)),
        ("period_duration".into(), JsonValue::Int(value.period_duration)),
        ("luteal_phase_days".into(), JsonValue::Int(value.luteal_phase_days)),
        ("pregnancy_mode".into(), JsonValue::Bool(value.is_pregnancy_mode)),
        ("pregnancy_due_date".into(), JsonValue::Str(value.pregnancy_due_date.clone())),
    ];
    let mut out = String::new();
    write_value(&JsonValue::Object(pairs), &mut out);
    out
}

pub fn profile_payload(value: &OnboardingProfile) -> String {
    let pairs: Vec<(String, JsonValue)> = vec![
        ("selected_goals".into(), JsonValue::Str(value.selected_goals_csv.clone())),
        ("last_period_date".into(), JsonValue::Str(value.last_period_date_string.clone())),
        ("last_period_end_date".into(), JsonValue::Str(value.last_period_end_date_string.clone())),
        ("approximate_period_choice".into(), JsonValue::Str(value.approximate_period_choice.clone())),
        ("cycle_length_option".into(), JsonValue::Str(value.cycle_length_option.clone())),
        ("cycle_length_days".into(), JsonValue::Int(value.cycle_length_days)),
        ("period_duration_days".into(), JsonValue::Int(value.period_duration_days)),
        ("typical_symptoms".into(), JsonValue::Str(value.typical_symptoms_csv.clone())),
        ("typical_energy".into(), JsonValue::Str(value.typical_energy.clone())),
        ("typical_sleep_quality".into(), JsonValue::Str(value.typical_sleep_quality.clone())),
        ("has_completed_deep_baseline".into(), JsonValue::Bool(value.has_completed_deep_baseline)),
    ];
    let mut out = String::new();
    write_value(&JsonValue::Object(pairs), &mut out);
    out
}

pub fn sha256_hex(value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    digest.iter().map(|b| format!("{:02x}", b)).collect()
}

/// hash(payloadJson): sha256 of the payload, or of the literal "null".
pub fn hash(payload_json: Option<&str>) -> String {
    sha256_hex(payload_json.unwrap_or("null"))
}

// ---------- Payload parsing (verbatim validator order/error vocabulary) ----------

const LOG_KEYS: [&str; 15] = [
    "date", "flow", "energy", "mood", "symptoms", "vaginal_health", "urinary_health",
    "cervical_mucus", "ovulation_test", "bbt_celsius", "sleep_hours", "sleep_quality",
    "water_glasses", "notes", "pain_intensity",
];

const SETTINGS_KEYS: [&str; 7] = [
    "selected_goals", "last_period_start_date", "average_cycle_length", "period_duration",
    "luteal_phase_days", "pregnancy_mode", "pregnancy_due_date",
];

fn obj_get<'v>(o: &'v [(String, JsonValue)], key: &str) -> Option<&'v JsonValue> {
    o.iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

fn required<'v>(o: &'v [(String, JsonValue)], key: &str) -> Result<&'v JsonValue, String> {
    match obj_get(o, key) {
        None | Some(JsonValue::Null) => Err(format!("missing_{}", key)),
        Some(v) => Ok(v),
    }
}

fn text(o: &[(String, JsonValue)], key: &str) -> Result<String, String> {
    match required(o, key)? {
        JsonValue::Str(s) => {
            // Java String.length() counts UTF-16 code units.
            if s.encode_utf16().count() > 4096 {
                Err(format!("long_{}", key))
            } else {
                Ok(s.clone())
            }
        }
        _ => Err(format!("type_{}", key)),
    }
}

fn bool_field(o: &[(String, JsonValue)], key: &str) -> Result<bool, String> {
    match required(o, key)? {
        JsonValue::Bool(b) => Ok(*b),
        _ => Err(format!("type_{}", key)),
    }
}

fn integer(o: &[(String, JsonValue)], key: &str, range: (i32, i32)) -> Result<i32, String> {
    let v = required(o, key)?;
    if v.is_number() {
        let d = v.to_f64();
        if d.is_finite() && d % 1.0 == 0.0 && d >= range.0 as f64 && d <= range.1 as f64 {
            let n = d as i32;
            if n < range.0 || n > range.1 {
                return Err(format!("range_{}", key));
            }
            return Ok(n);
        }
    }
    Err(format!("type_{}", key))
}

fn decimal(o: &[(String, JsonValue)], key: &str, range: (f64, f64)) -> Result<f32, String> {
    match required(o, key)? {
        v if v.is_number() => {
            let n = v.to_f64();
            if !n.is_finite() || n < range.0 || n > range.1 {
                return Err(format!("range_{}", key));
            }
            Ok(n as f32)
        }
        _ => Err(format!("type_{}", key)),
    }
}

fn check_keys(o: &[(String, JsonValue)], allowed: &[&str]) -> Result<(), String> {
    for (k, _) in o {
        if !allowed.contains(&k.as_str()) {
            return Err(format!("unknown_{}", k));
        }
    }
    Ok(())
}

/// Strict ISO LocalDate.parse("yyyy-MM-dd") equivalent.
pub fn is_valid_local_date(s: &str) -> bool {
    let b = s.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return false;
    }
    if !b.iter().enumerate().all(|(i, c)| i == 4 || i == 7 || c.is_ascii_digit()) {
        return false;
    }
    let year: i32 = s[0..4].parse().unwrap();
    let month: u32 = s[5..7].parse().unwrap();
    let day: u32 = s[8..10].parse().unwrap();
    if !(1..=12).contains(&month) || day == 0 {
        return false;
    }
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let dim = [31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    day <= dim[(month - 1) as usize]
}

fn parse_object(payload: &str) -> Result<Vec<(String, JsonValue)>, String> {
    match JsonParser::new(payload).parse()? {
        JsonValue::Object(pairs) => Ok(pairs),
        _ => Err("not_an_object".to_string()),
    }
}

pub fn parse_log_payload(payload: &str, record_id: &str) -> Result<CycleDayLog, String> {
    let o = parse_object(payload)?;
    check_keys(&o, &LOG_KEYS)?;
    let date = text(&o, "date")?;
    if date != record_id || !is_valid_local_date(&date) {
        return Err("record_identity".to_string());
    }
    Ok(CycleDayLog {
        date,
        flow: text(&o, "flow")?,
        energy: text(&o, "energy")?,
        mood: text(&o, "mood")?,
        symptoms: text(&o, "symptoms")?,
        vaginal_health: text(&o, "vaginal_health")?,
        urinary_health: text(&o, "urinary_health")?,
        cervical_mucus: text(&o, "cervical_mucus")?,
        ovulation_test: text(&o, "ovulation_test")?,
        bbt_celsius: decimal(&o, "bbt_celsius", (0.0, 60.0))?,
        sleep_hours: decimal(&o, "sleep_hours", (0.0, 24.0))?,
        sleep_quality: text(&o, "sleep_quality")?,
        water_glasses: integer(&o, "water_glasses", (0, 100))?,
        notes: text(&o, "notes")?,
        pain_intensity: integer(&o, "pain_intensity", (0, 10))?,
    })
}

pub fn parse_settings_payload(
    payload: &str,
    record_id: &str,
    local: Option<&CycleSettings>,
) -> Result<CycleSettings, String> {
    if record_id != "settings" {
        return Err("record_identity".to_string());
    }
    let o = parse_object(payload)?;
    check_keys(&o, &SETTINGS_KEYS)?;
    let date = text(&o, "last_period_start_date")?;
    if !date.is_empty() && !is_valid_local_date(&date) {
        return Err("invalid_date".to_string());
    }
    let due = text(&o, "pregnancy_due_date")?;
    if !due.is_empty() && !is_valid_local_date(&due) {
        return Err("invalid_due_date".to_string());
    }
    // `local` mirrors the Kotlin `(local ?: CycleSettings()).copy(...)` base;
    // every field is overwritten by payload values, so only the defaults
    // matter for documentation. Kotlin evaluates the same argument order.
    let _ = local;
    Ok(CycleSettings {
        selected_goals_csv: text(&o, "selected_goals")?,
        last_period_start_date: date,
        average_cycle_length: integer(&o, "average_cycle_length", (21, 45))?,
        period_duration: integer(&o, "period_duration", (2, 10))?,
        luteal_phase_days: integer(&o, "luteal_phase_days", (1, 30))?,
        is_pregnancy_mode: bool_field(&o, "pregnancy_mode")?,
        pregnancy_due_date: due,
    })
}

pub fn parse_profile_payload(
    payload: &str,
    record_id: &str,
    local: Option<&OnboardingProfile>,
) -> Result<OnboardingProfile, String> {
    if record_id != "profile" {
        return Err("record_identity".to_string());
    }
    let o = parse_object(payload)?;
    // Unknown keys are intentionally ignored for forward/backward compat.
    let date = text(&o, "last_period_date")?;
    if !date.is_empty() && !is_valid_local_date(&date) {
        return Err("invalid_date".to_string());
    }
    let end_date = match obj_get(&o, "last_period_end_date") {
        None | Some(JsonValue::Null) => String::new(),
        Some(JsonValue::Str(s)) => s.clone(),
        _ => return Err("type_last_period_end_date".to_string()),
    };
    if !end_date.is_empty() && !is_valid_local_date(&end_date) {
        return Err("invalid_end_date".to_string());
    }
    // Restored data cannot certify age, accept terms, complete onboarding,
    // or change account mode.
    // Kotlin: (local ?: OnboardingProfile(isCompleted=false, completedAtTimestamp=0,
    // isAgeVerified=false, isTermsAgreed=false)).copy(...) - the safety flags
    // are always reset regardless of the local base, and every payload field
    // is overwritten, so the base contributes nothing to the result.
    let _ = local;
    Ok(OnboardingProfile {
        selected_goals_csv: text(&o, "selected_goals")?,
        last_period_date_string: date,
        last_period_end_date_string: end_date,
        approximate_period_choice: text(&o, "approximate_period_choice")?,
        cycle_length_option: text(&o, "cycle_length_option")?,
        cycle_length_days: integer(&o, "cycle_length_days", (21, 45))?,
        period_duration_days: integer(&o, "period_duration_days", (2, 10))?,
        typical_symptoms_csv: text(&o, "typical_symptoms")?,
        typical_energy: text(&o, "typical_energy")?,
        typical_sleep_quality: text(&o, "typical_sleep_quality")?,
        has_completed_deep_baseline: bool_field(&o, "has_completed_deep_baseline")?,
        is_completed: false,
        completed_at_timestamp: 0,
        is_age_verified: false,
        is_terms_agreed: false,
    })
}
