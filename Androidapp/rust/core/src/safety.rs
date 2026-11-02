//! Local-only safety triage - a conservative prompt for care, not a diagnosis.
//! Port of domain/safety/SafetyEngine.kt (main@31b88f5), including the
//! verbatim level ordering, titles, messages, advice strings, and the
//! negation-aware free-text screen in evaluateInput. No new clinical rules.
//!
//! Regex parity note (validated against Android platform source, not assumed):
//! on-device, Android's java.util.regex delegates to ICU4C via libcore's
//! com.android.icu.util.regex.PatternNative (icu::RegexPattern::compile), and
//! the Android Pattern reference defines \w as
//! [\p{alpha}\p{gc=Mark}\p{digit}\p{gc=Connector_Punctuation}\p{Join_Control}]
//! - Unicode-aware by default, unlike desktop OpenJDK. Rust's default \b is
//! Unicode-aware over the equivalent set, so plain \b matches DEVICE behavior
//! and (?-u) would have been wrong there. Sources:
//!   https://developer.android.com/reference/java/util/regex/Pattern
//!   https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-appcompat-release/android-34/com/android/icu/util/regex/PatternNative.java
//! KNOWN TEST-ENV GAP: host JVM unit tests (testDebugUnitTest) use OpenJDK
//! regex (ASCII \b), so the cross-language oracle measures host-JVM semantics;
//! on non-ASCII-adjacent terms (corpus cases i13/i14) device and host JVM can
//! legitimately differ. The Rust port targets DEVICE semantics; any host-JVM
//! divergence the oracle surfaces on those cases is a test-environment
//! artifact to document, not a port bug.
use crate::kotlin_text::{kotlin_trim};

use regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum SafetyLevel {
    Low,
    Monitor,
    Review,
    SeekProfessionalAdvice,
    Urgent,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafetyEvaluation {
    pub level: SafetyLevel,
    pub title: &'static str,
    pub message: String,
    pub action_advice: String,
    pub is_red_flag: bool,
}

/// Log fields this engine reads (verbatim names from CycleDayLog).
#[derive(Debug, Clone, Default)]
pub struct SafetyLog {
    pub flow_level: String,
    pub symptoms_csv: String,
    pub vaginal_health_csv: String,
    pub urinary_health_csv: String,
    pub pain_intensity: i32,
}

fn values(csv: &str) -> Vec<String> {
    csv.split(',')
        .map(|s| kotlin_trim(s).to_lowercase().replace(' ', "_"))
        .filter(|s| !s.is_empty())
        .collect()
}

fn low(message: &str) -> SafetyEvaluation {
    SafetyEvaluation {
        level: SafetyLevel::Low,
        title: "Routine health status",
        message: message.to_string(),
        action_advice: "Continue tracking and seek care for severe or worsening symptoms.".to_string(),
        is_red_flag: false,
    }
}

fn review(message: &str, advice: &str) -> SafetyEvaluation {
    SafetyEvaluation {
        level: SafetyLevel::Review,
        title: "Review recommended",
        message: message.to_string(),
        action_advice: advice.to_string(),
        is_red_flag: false,
    }
}

fn seek(message: &str, advice: &str) -> SafetyEvaluation {
    SafetyEvaluation {
        level: SafetyLevel::SeekProfessionalAdvice,
        title: "Professional advice recommended",
        message: message.to_string(),
        action_advice: advice.to_string(),
        is_red_flag: true,
    }
}

fn urgent(message: &str, advice: &str) -> SafetyEvaluation {
    SafetyEvaluation {
        level: SafetyLevel::Urgent,
        title: "Urgent medical attention advised",
        message: message.to_string(),
        action_advice: advice.to_string(),
        is_red_flag: true,
    }
}

pub fn evaluate(log: Option<&SafetyLog>) -> SafetyEvaluation {
    let Some(log) = log else {
        return low("No acute symptoms are recorded.");
    };
    let symptoms = values(&log.symptoms_csv);
    let vaginal = values(&log.vaginal_health_csv);
    let urinary = values(&log.urinary_health_csv);
    let fainting = symptoms.iter().any(|s| s == "fainting");
    let heavy = kotlin_trim(&log.flow_level).to_uppercase() == "UNUSUALLY_HEAVY";
    if fainting && heavy {
        return urgent(
            "Fainting and unusually heavy bleeding were recorded together.",
            "Please seek urgent medical care now, especially if symptoms are continuing, worsening, or you feel unsafe.",
        );
    }
    if heavy && log.pain_intensity >= 8 {
        return urgent(
            "Unusually heavy bleeding and severe pain were recorded together.",
            "Please seek urgent medical care now. Do not rely on this app to assess the cause.",
        );
    }
    if fainting {
        return urgent(
            "A fainting episode was recorded.",
            "Please seek prompt medical evaluation and avoid driving if you feel faint or unwell.",
        );
    }
    if vaginal.iter().any(|s| s == "sores_lesions") {
        return seek(
            "New sores or lesions were recorded.",
            "Arrange a clinical assessment, and avoid products that irritate the area.",
        );
    }
    if heavy {
        return seek(
            "Unusually heavy bleeding was recorded.",
            "Please contact a healthcare professional promptly; seek urgent help if it is worsening or you feel faint.",
        );
    }
    if symptoms.iter().any(|s| s == "severe_cramps") || log.pain_intensity >= 7 {
        return seek(
            "Severe pain was recorded.",
            "Arrange a clinical review, particularly if pain is new, persistent, or worsening.",
        );
    }
    if urinary.iter().any(|s| s == "urinary_burning")
        && (urinary.iter().any(|s| s == "urinary_urgency")
            || urinary.iter().any(|s| s == "urinary_frequency"))
    {
        return seek(
            "Several urinary symptoms were recorded together.",
            "Consider prompt clinical advice for assessment. Seek urgent help for fever, flank pain, or worsening illness.",
        );
    }
    if vaginal
        .iter()
        .any(|s| matches!(s.as_str(), "vaginal_itching" | "vaginal_burning" | "unusual_odor"))
    {
        return review(
            "Vaginal irritation or discharge changes were recorded.",
            "Avoid douching and scented irritants; seek clinical advice if symptoms persist or worsen.",
        );
    }
    if !symptoms.is_empty() || (3..=6).contains(&log.pain_intensity) {
        return SafetyEvaluation {
            level: SafetyLevel::Monitor,
            title: "Symptoms to monitor",
            message: "Symptoms were recorded without an urgent combination.".to_string(),
            action_advice: "Notice whether they persist, change, or worsen.".to_string(),
            is_red_flag: false,
        };
    }
    low("No acute symptoms are recorded.")
}

fn split_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"[,.;!?]|\b(?:but|and|however)\b").unwrap())
}

fn negation_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"\b(?:no|not|never|without|haven't|dont|don't|didn't|doesn't|isn't|aren't)\b").unwrap()
    })
}

/// Negation stops at punctuation/conjunctions, so a second positive symptom survives.
fn positive_text(text: &str) -> String {
    split_re()
        .split(text)
        .map(|clause| if negation_re().is_match(clause) { "" } else { clause })
        .collect::<Vec<_>>()
        .join(" ")
}

fn contains_any(text: &str, terms: &[&str]) -> bool {
    terms.iter().any(|term| {
        let re = Regex::new(&format!(r"\b{}[a-z]*\b", regex::escape(term))).unwrap();
        re.is_match(text)
    })
}

fn limited_assessment(base: &SafetyEvaluation) -> SafetyEvaluation {
    let limitation = "This local check cannot reliably assess every symptom description or language. No matched warning does not mean symptoms are safe.";
    SafetyEvaluation {
        level: SafetyLevel::Review,
        title: "Safety assessment limited",
        message: if base.level != SafetyLevel::Low {
            format!("{} {}", base.message, limitation)
        } else {
            limitation.to_string()
        },
        action_advice: "If you feel seriously unwell or symptoms are severe or worsening, seek urgent medical care. Otherwise ask a qualified healthcare professional; use an interpreter or someone you trust if language is a barrier.".to_string(),
        is_red_flag: false,
    }
}

/// Evaluates NEW text before any network request or account/context gating.
/// Never writes. Existing-log findings are retained; explicitly negated input
/// is not promoted to a flag.
pub fn evaluate_input(raw_input: &str, existing_log: Option<&SafetyLog>) -> SafetyEvaluation {
    let text = kotlin_trim(raw_input).to_lowercase();
    let base = evaluate(existing_log);
    if text.is_empty() {
        return base;
    }
    let positive = positive_text(&text);
    let saved_symptoms = existing_log.map(|l| values(&l.symptoms_csv)).unwrap_or_default();
    let fainting = contains_any(&positive, &["faint", "passed out", "lost consciousness"])
        || saved_symptoms.iter().any(|s| s == "fainting");
    let heavy = contains_any(
        &positive,
        &["unusually heavy", "very heavy", "soaking", "soaked", "heavy bleeding", "lots of blood"],
    ) || existing_log
        .map(|l| kotlin_trim(&l.flow_level).to_uppercase() == "UNUSUALLY_HEAVY")
        .unwrap_or(false);
    let severe_pain = contains_any(
        &positive,
        &[
            "severe pain",
            "severe pelvic pain",
            "severe cramps",
            "unbearable pain",
            "excruciating",
            "pain is killing",
            "can't stand the pain",
        ],
    ) || existing_log.map(|l| l.pain_intensity >= 7).unwrap_or(false)
        || saved_symptoms.iter().any(|s| s == "severe_cramps");
    let sores = contains_any(&positive, &["sores", "lesions", "blisters"]);
    let urinary_burning =
        contains_any(&positive, &["burning when i pee", "burning urination", "burning to pee"]);
    let urinary_other = contains_any(
        &positive,
        &["urgent to pee", "urinary urgency", "peeing often", "frequent urination"],
    );
    let candidate = if fainting && heavy {
        urgent(
            "Fainting and unusually heavy bleeding were mentioned together.",
            "Please seek urgent medical care now, especially if symptoms are continuing or worsening.",
        )
    } else if heavy && severe_pain {
        urgent(
            "Unusually heavy bleeding and severe pain were mentioned together.",
            "Please seek urgent medical care now; this app cannot assess the cause.",
        )
    } else if fainting {
        urgent(
            "A fainting episode was mentioned.",
            "Please seek prompt medical evaluation and avoid driving if you feel faint or unwell.",
        )
    } else if sores {
        seek(
            "New sores or lesions were mentioned.",
            "Arrange a clinical assessment and avoid products that irritate the area.",
        )
    } else if heavy {
        seek(
            "Unusually heavy bleeding was mentioned.",
            "Please contact a healthcare professional promptly; seek urgent help if it worsens or you feel faint.",
        )
    } else if severe_pain {
        seek(
            "Severe pain was mentioned.",
            "Arrange a clinical review, particularly if pain is new, persistent, or worsening.",
        )
    } else if urinary_burning && urinary_other {
        seek(
            "Several urinary symptoms were mentioned together.",
            "Consider prompt clinical advice for assessment; seek urgent help for fever, flank pain, or worsening illness.",
        )
    } else {
        // A keyword miss is not a negative safety screen. This also covers
        // Latin-script languages and mixed input; script detection cannot
        // establish understanding.
        limited_assessment(&base)
    };
    if base.level > candidate.level {
        base
    } else {
        candidate
    }
}
