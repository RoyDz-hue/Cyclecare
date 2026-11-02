//! Port of domain/ai/CareConversationPolicy.kt (main@31b88f5), including
//! ProposedCareLog and CareLogConsentGate from the same file.
//!
//! Regex semantics: the Kotlin patterns run under java.util.regex defaults,
//! where \b, \w and \s are ASCII-only. The Rust regexes are built with
//! unicode(false) so \b/\s match Java's ASCII behavior exactly. JVM probe
//! CarePolicyParity.kt (parity/ai, corpus il01-il22, m01-m03, three
//! compileContext scenarios) executed against the verbatim Kotlin source and
//! locked here byte-for-byte.

use crate::cycle_math::parse_local_date_iso;
use crate::kotlin_text::{kotlin_is_blank, kotlin_trim};
use crate::log_parser::{parse as parse_log, ParsedLogInterpretation};
use crate::sync_codec::java_double_to_string;
use chrono::{Duration, NaiveDate};
use regex::{Regex, RegexBuilder};
use std::sync::{Mutex, OnceLock};

pub const MAX_QUESTION_LENGTH: usize = 1200;
pub const MAX_CONTEXT_LENGTH: usize = 2400;

fn java_regex(pattern: &str) -> Regex {
    RegexBuilder::new(pattern)
        .unicode(false)
        .build()
        .expect("policy regex compiles")
}

fn explicit_log_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| java_regex(r"^(please\s+)?(log|record|save|note)\b"))
}
fn educational_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        java_regex(r"^(why|what|how|can|could|does|do|is|are|when|should|would|tell me|explain)\b")
    })
}
fn personal_report_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        java_regex(
            r"\b(i am|i'm|i have|i've|i feel|i felt|my period started|my flow is|nimechoka|nina maumivu)\b",
        )
    })
}
fn topic_re(pattern: &str) -> Regex {
    java_regex(pattern)
}

pub fn is_logging_intent(input: &str) -> bool {
    let text = kotlin_trim(input).to_lowercase();
    let explicit_log = explicit_log_re().is_match(&text)
        || text.starts_with("i want to log ")
        || text.starts_with("i'd like to log ");
    let educational = text.ends_with('?') || educational_re().is_match(&text);
    let personal_report = personal_report_re().is_match(&text);
    explicit_log || (personal_report && !educational)
}

/// Full CycleDayLog Room entity (cycle_day_logs DDL, 15 columns), so
/// merge() preserves every non-wire field like Kotlin's data class copy().
#[derive(Debug, Clone, PartialEq)]
pub struct CycleDayLog {
    pub date_string: String,
    pub flow_level: String,
    pub energy_level: String,
    pub mood_level: String,
    pub symptoms_csv: String,
    pub vaginal_health_csv: String,
    pub urinary_health_csv: String,
    pub cervical_mucus: String,
    pub ovulation_test: String,
    pub bbt_celsius: f64,
    pub sleep_hours: f64,
    pub sleep_quality: String,
    pub water_glasses: i64,
    pub notes: String,
    pub pain_intensity: i32,
}

/// Full CycleSettings Room entity (cycle_settings DDL, 15 columns).
#[derive(Debug, Clone, PartialEq)]
pub struct CycleSettings {
    pub id: i64,
    pub is_onboarding_completed: bool,
    pub selected_goals_csv: String,
    pub last_period_start_date: String,
    pub average_cycle_length: i64,
    pub period_duration: i64,
    pub luteal_phase_days: i64,
    pub is_app_lock_enabled: bool,
    pub lock_timeout_minutes: i64,
    pub is_discreet_notification_mode: bool,
    pub is_pregnancy_mode: bool,
    pub pregnancy_due_date: String,
    pub remind_period: bool,
    pub remind_fertile: bool,
    pub remind_daily_log: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ProposedCareLog {
    pub id: String,
    pub owner_id: String,
    pub date: String,
    pub interpretation: ParsedLogInterpretation,
}

fn uuid_v4() -> String {
    let mut b = [0u8; 16];
    fill_entropy(&mut b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let mut s = String::with_capacity(36);
    for (i, byte) in b.iter().enumerate() {
        if i == 4 || i == 6 || i == 8 || i == 10 {
            s.push('-');
        }
        s.push_str(&format!("{:02x}", byte));
    }
    s
}

/// OS entropy on the real targets (Linux dev, Android production). Matches
/// Kotlin UUID.randomUUID (SecureRandom) semantics.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn fill_entropy(b: &mut [u8; 16]) {
    use std::io::Read;
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(b))
        .expect("/dev/urandom readable");
}

#[cfg(all(feature = "host-fixture-entropy", feature = "web-secure-entropy"))]
compile_error!(
    "features `host-fixture-entropy` and `web-secure-entropy` are mutually exclusive: fixture determinism can never ship as secure entropy"
);

/// Unsupported targets stay explicitly unavailable: without a secure
/// provider they fail at compile time rather than silently minting tokens.
#[cfg(all(
    not(any(target_os = "linux", target_os = "android")),
    not(feature = "host-fixture-entropy"),
    not(all(target_arch = "wasm32", feature = "web-secure-entropy"))
))]
compile_error!(
    "no OS entropy on this target: enable `host-fixture-entropy` for host-only fixtures, `web-secure-entropy` for the secure wasm32 browser path, or add a secure provider before production use"
);

/// SECURE wasm32 browser entropy: crypto.getRandomValues via getrandom's js
/// backend - the production-shape browser path (CSPRNG, no deterministic
/// seed, no fixture counter). Matches Kotlin UUID.randomUUID (SecureRandom)
/// semantics like the OS path.
#[cfg(all(
    target_arch = "wasm32",
    feature = "web-secure-entropy",
    not(feature = "host-fixture-entropy")
))]
fn fill_entropy(b: &mut [u8; 16]) {
    getrandom::getrandom(b).expect("crypto.getRandomValues available in a secure browser context");
}

/// HOST-FIXTURE-ONLY fallback (wasm32 browser demo, feature-gated): no OS
/// entropy exists there. The proposal id is an ephemeral gate-matching
/// token, never a security identifier; uniqueness within a host session
/// comes from an atomic counter through splitmix64. Ids repeat across
/// fresh host loads, which the single-pending gate tolerates. This does
/// NOT implement the production token generator; production Android keeps
/// the OS-entropy path above.
#[cfg(all(
    not(any(target_os = "linux", target_os = "android")),
    feature = "host-fixture-entropy",
    not(feature = "web-secure-entropy")
))]
fn fill_entropy(b: &mut [u8; 16]) {
    use std::sync::atomic::{AtomicU64, Ordering};
    static CTR: AtomicU64 = AtomicU64::new(0x9e37_79b9_7f4a_7c15);
    for chunk in b.chunks_mut(8) {
        let x = CTR.fetch_add(0x9e37_79b9_7f4a_7c15, Ordering::Relaxed);
        let mut z = x;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        z ^= z >> 31;
        let bytes = z.to_le_bytes();
        chunk.copy_from_slice(&bytes[..chunk.len()]);
    }
}

pub fn proposal(owner_id: &str, date: &str, input: &str) -> Option<ProposedCareLog> {
    if !is_logging_intent(input) {
        return None;
    }
    let truncated: String = input.chars().take(MAX_QUESTION_LENGTH).collect();
    let parsed = parse_log(&truncated);
    if parsed.symptoms_result.is_empty()
        && parsed.flow_level_result.is_none()
        && parsed.energy_result.is_none()
        && parsed.mood_result.is_none()
        && parsed.pain_intensity_result <= 0
    {
        return None;
    }
    Some(ProposedCareLog {
        id: uuid_v4(),
        owner_id: owner_id.to_string(),
        date: date.to_string(),
        interpretation: parsed,
    })
}

/// existing.copy(flowLevel = parsed ?: existing, ...) - every other entity
/// field is preserved untouched. The raw conversation is intentionally not
/// copied into permanent health notes.
pub fn merge(existing: &CycleDayLog, parsed: &ParsedLogInterpretation) -> CycleDayLog {
    let mut merged = existing.clone();
    if let Some(flow) = parsed.flow_level_result {
        merged.flow_level = flow.to_string();
    }
    if let Some(energy) = parsed.energy_result {
        merged.energy_level = energy.to_string();
    }
    if let Some(mood) = parsed.mood_result {
        merged.mood_level = mood.to_string();
    }
    // (existing.split(",") + parsed.symptoms).map trim .filter !blank .distinct() join ","
    let mut parts: Vec<String> = Vec::new();
    for piece in existing
        .symptoms_csv
        .split(',')
        .chain(parsed.symptoms_result.iter().map(|s| *s))
    {
        let t = kotlin_trim(piece);
        if !kotlin_is_blank(t) && !parts.iter().any(|p| p == t) {
            parts.push(t.to_string());
        }
    }
    merged.symptoms_csv = parts.join(",");
    if parsed.pain_intensity_result > 0 {
        merged.pain_intensity = parsed.pain_intensity_result.clamp(0, 10);
    }
    merged
}

/// Only relevant, structured observations; never notes, identity or
/// intimate-health fields.
pub fn compile_context(
    question: &str,
    logs: &[CycleDayLog],
    settings: &CycleSettings,
    today: NaiveDate,
) -> String {
    let q = question.to_lowercase();
    let cycle = topic_re("cycle|period|flow|bleed|timing").is_match(&q);
    let energy = topic_re("energy|tired|fatigue|exhaust|sleep|rest").is_match(&q);
    let mood = topic_re("mood|anxious|stress|sad|emotion|irritab").is_match(&q);
    let symptoms =
        topic_re("symptom|pain|cramp|headache|migraine|bloat|nausea|pattern|changed|change")
            .is_match(&q);
    let mut out = String::new();
    out.push_str("User-approved context. Self-reported observations, not diagnosis.\n");
    if cycle && !settings.last_period_start_date.is_empty() {
        out.push_str(&format!(
            "Cycle anchor: {}; configured cycle length {} days (calendar estimate only).\n",
            settings.last_period_start_date, settings.average_cycle_length
        ));
    }
    let window_start = today - Duration::days(30);
    let mut recent: Vec<&CycleDayLog> = logs
        .iter()
        .filter(|log| {
            match parse_local_date_iso(&log.date_string) {
                Some(d) => d <= today && d >= window_start,
                None => false,
            }
        })
        .collect();
    recent.sort_by(|a, b| b.date_string.cmp(&a.date_string));
    for log in recent.iter().take(8) {
        let mut fields: Vec<String> = Vec::new();
        if cycle && !log.flow_level.is_empty() {
            fields.push(format!("flow={}", log.flow_level));
        }
        if energy && !log.energy_level.is_empty() {
            fields.push(format!("energy={}", log.energy_level));
        }
        if energy && log.sleep_hours > 0.0 {
            fields.push(format!("sleep={} hours", java_double_to_string(log.sleep_hours)));
        }
        if energy && !log.sleep_quality.is_empty() {
            fields.push(format!("sleep quality={}", log.sleep_quality));
        }
        if mood && !log.mood_level.is_empty() {
            fields.push(format!("mood={}", log.mood_level));
        }
        if symptoms && !log.symptoms_csv.is_empty() {
            let taken: String = log.symptoms_csv.chars().take(180).collect();
            fields.push(format!("symptoms={}", taken));
        }
        if symptoms && log.pain_intensity > 0 {
            fields.push(format!("pain={}/10", log.pain_intensity));
        }
        if !fields.is_empty() {
            out.push_str(&format!("{}: {}\n", log.date_string, fields.join("; ")));
        }
    }
    if !cycle && !energy && !mood && !symptoms {
        out.push_str("No relevant saved health observations selected.\n");
    }
    out.push_str(
        "No free-text notes, account details, sexual activity or intimate-health records included.",
    );
    out.chars().take(MAX_CONTEXT_LENGTH).collect()
}

/// Ephemeral proposal gate. Creating a proposal never persists health data
/// or grants rewards. Kotlin @Synchronized methods -> one Mutex.
pub struct CareLogConsentGate {
    pending: Mutex<Option<ProposedCareLog>>,
}

impl CareLogConsentGate {
    pub fn new() -> Self {
        CareLogConsentGate {
            pending: Mutex::new(None),
        }
    }

    pub fn propose(&self, owner_id: &str, date: &str, raw_text: &str) -> Option<ProposedCareLog> {
        let next = proposal(owner_id, date, raw_text);
        let mut guard = self.pending.lock().expect("consent gate lock");
        *guard = next.clone();
        next
    }

    /// Consume before launching a write, making double taps/retries a no-op.
    pub fn confirm(&self, id: &str, owner_id: &str) -> Option<ProposedCareLog> {
        let mut guard = self.pending.lock().expect("consent gate lock");
        let proposal = guard.as_ref()?;
        if proposal.id != id || proposal.owner_id != owner_id {
            return None;
        }
        guard.take()
    }

    pub fn cancel(&self) {
        let mut guard = self.pending.lock().expect("consent gate lock");
        *guard = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn log(date: &str) -> CycleDayLog {
        CycleDayLog {
            date_string: date.to_string(),
            flow_level: String::new(),
            energy_level: String::new(),
            mood_level: String::new(),
            symptoms_csv: String::new(),
            vaginal_health_csv: String::new(),
            urinary_health_csv: String::new(),
            cervical_mucus: String::new(),
            ovulation_test: String::new(),
            bbt_celsius: 0.0,
            sleep_hours: 0.0,
            sleep_quality: String::new(),
            water_glasses: 0,
            notes: String::new(),
            pain_intensity: 0,
        }
    }

    fn settings() -> CycleSettings {
        CycleSettings {
            id: 1,
            is_onboarding_completed: true,
            selected_goals_csv: String::new(),
            last_period_start_date: String::new(),
            average_cycle_length: 28,
            period_duration: 5,
            luteal_phase_days: 14,
            is_app_lock_enabled: false,
            lock_timeout_minutes: 5,
            is_discreet_notification_mode: false,
            is_pregnancy_mode: false,
            pregnancy_due_date: String::new(),
            remind_period: true,
            remind_fertile: true,
            remind_daily_log: true,
        }
    }

    #[test]
    fn intent_corpus_locked_to_jvm() {
        // Executed against verbatim Kotlin under java.util.regex (il01-il22).
        let cases: &[(&str, bool)] = &[
            ("log my period started today", true),
            ("please log headache", true),
            ("record this", true),
            ("save", true),
            ("why is my cycle late?", false),
            ("my period started today", true),
            ("my period started?", false),
            ("i feel tired", true),
            ("i'm tired", true),
            ("nimechoka sana", true),
            ("how do i log my period", false),
            ("i want to log my flow", true),
            ("  Log  cramps ", true),
            ("dialog", false),
            ("logger", false),
            ("catalog", false),
            ("nina maumivu ya tumbo", true),
            ("i've had cramps all week", true),
            ("what should i eat for cramps?", false),
            ("tell me about ovulation", false),
            ("i'd like to log nausea", true),
            ("note to self", true),
        ];
        for (input, expected) in cases {
            assert_eq!(is_logging_intent(input), *expected, "input: {input:?}");
        }
    }

    #[test]
    fn merge_csv_corpus_locked_to_jvm() {
        // m01-m03 via the entity-level merge (symptoms-only parsed input).
        let parsed = |syms: &[&'static str]| ParsedLogInterpretation {
            raw_text: String::new(),
            items: Vec::new(),
            follow_up_question: None,
            flow_level_result: None,
            energy_result: None,
            mood_result: None,
            symptoms_result: syms.to_vec(),
            pain_intensity_result: 0,
        };
        let mut existing = log("2026-11-01");
        existing.symptoms_csv = "cramps, headache".to_string();
        existing.notes = "KEEP".to_string();
        existing.water_glasses = 7;
        let merged = merge(&existing, &parsed(&["headache", "nausea"]));
        assert_eq!(merged.symptoms_csv, "cramps,headache,nausea");
        assert_eq!(merged.notes, "KEEP"); // raw text never merged
        assert_eq!(merged.water_glasses, 7); // untouched fields preserved

        existing.symptoms_csv = String::new();
        let merged = merge(&existing, &parsed(&[" a ", "", "b"]));
        assert_eq!(merged.symptoms_csv, "a,b");

        existing.symptoms_csv = "x,y".to_string();
        let merged = merge(&existing, &parsed(&["y"]));
        assert_eq!(merged.symptoms_csv, "x,y");
    }

    #[test]
    fn merge_overrides_and_pain_coercion() {
        let parsed = ParsedLogInterpretation {
            raw_text: String::new(),
            items: Vec::new(),
            follow_up_question: None,
            flow_level_result: Some("heavy"),
            energy_result: None,
            mood_result: Some("calm"),
            symptoms_result: Vec::new(),
            pain_intensity_result: 14,
        };
        let mut existing = log("2026-11-01");
        existing.flow_level = "light".to_string();
        existing.mood_level = "sad".to_string();
        existing.pain_intensity = 3;
        let merged = merge(&existing, &parsed);
        assert_eq!(merged.flow_level, "heavy");
        assert_eq!(merged.mood_level, "calm");
        assert_eq!(merged.pain_intensity, 10); // coerceIn(0,10)
        // pain not in parsed -> existing kept
        let parsed_no_pain = ParsedLogInterpretation {
            pain_intensity_result: 0,
            ..parsed.clone()
        };
        let merged = merge(&existing, &parsed_no_pain);
        assert_eq!(merged.pain_intensity, 3);
    }

    fn ctx_logs() -> Vec<CycleDayLog> {
        let mut l1 = log("2026-11-01");
        l1.flow_level = "medium".into();
        l1.energy_level = "low".into();
        l1.sleep_hours = 6.5;
        l1.sleep_quality = "poor".into();
        l1.mood_level = "sad".into();
        l1.symptoms_csv = "cramps, headache".into();
        l1.pain_intensity = 6;
        let mut l2 = log("2026-10-01");
        l2.flow_level = "old".into();
        let mut l3 = log("garbage");
        l3.flow_level = "bad".into();
        let mut l4 = log("2026-11-03");
        l4.flow_level = "future".into();
        vec![l1, l2, l3, l4]
    }

    #[test]
    fn compile_context_locked_to_jvm() {
        let today = parse_local_date_iso("2026-11-02").unwrap();
        let mut s = settings();
        s.last_period_start_date = "2026-10-28".into();
        s.average_cycle_length = 29;
        let ctx = compile_context("how is my cycle timing?", &ctx_logs(), &s, today);
        assert_eq!(
            ctx,
            "User-approved context. Self-reported observations, not diagnosis.\n\
             Cycle anchor: 2026-10-28; configured cycle length 29 days (calendar estimate only).\n\
             2026-11-01: flow=medium\n\
             No free-text notes, account details, sexual activity or intimate-health records included."
        );
        let ctx = compile_context("what is photosynthesis?", &ctx_logs(), &s, today);
        assert_eq!(
            ctx,
            "User-approved context. Self-reported observations, not diagnosis.\n\
             No relevant saved health observations selected.\n\
             No free-text notes, account details, sexual activity or intimate-health records included."
        );
        let s2 = settings();
        let ctx = compile_context("my cramps pattern changed", &ctx_logs(), &s2, today);
        assert_eq!(
            ctx,
            "User-approved context. Self-reported observations, not diagnosis.\n\
             2026-11-01: symptoms=cramps, headache; pain=6/10\n\
             No free-text notes, account details, sexual activity or intimate-health records included."
        );
    }

    #[test]
    fn uuid_v4_shape_and_uniqueness() {
        let a = uuid_v4();
        let b = uuid_v4();
        assert_eq!(a.len(), 36);
        assert_ne!(a, b);
        assert_eq!(a.as_bytes()[14], b'4');
        assert!(matches!(a.as_bytes()[19], b'8' | b'9' | b'a' | b'b'));
    }

    #[test]
    fn proposal_gates_and_consent_flow() {
        // Not a logging intent -> no proposal.
        assert!(proposal("o1", "2026-11-02", "what is photosynthesis?").is_none());
        // Logging intent with no parseable content -> no proposal.
        assert!(proposal("o1", "2026-11-02", "log").is_none());
        // Real report -> proposal with v4 uuid shape and the parsed fields.
        let p = proposal("o1", "2026-11-02", "my period started today").expect("proposal");
        assert_eq!(p.owner_id, "o1");
        assert_eq!(p.date, "2026-11-02");
        assert_eq!(p.id.len(), 36);
        assert_eq!(p.id.as_bytes()[14], b'4');

        let gate = CareLogConsentGate::new();
        let p = gate
            .propose("o1", "2026-11-02", "i have cramps")
            .expect("pending");
        // Wrong owner or id -> confirm refuses and keeps pending.
        assert!(gate.confirm(&p.id, "other").is_none());
        assert!(gate.confirm("bogus", "o1").is_none());
        let confirmed = gate.confirm(&p.id, "o1").expect("confirm");
        assert_eq!(confirmed.id, p.id);
        // Consumed: double confirm is a no-op.
        assert!(gate.confirm(&p.id, "o1").is_none());
        // Cancel clears a pending proposal.
        let p2 = gate.propose("o1", "2026-11-02", "i feel tired").expect("pending");
        gate.cancel();
        assert!(gate.confirm(&p2.id, "o1").is_none());
    }
}
