//! Natural-language daily-log parser.
//! Port of domain/parser/NaturalLanguageLogParser.kt (main@31b88f5),
//! including East African Sheng/Swahili vocabulary. Guaranteed 100% offline
//! execution with zero writes: this function only builds a preview
//! interpretation; saving is a separate, user-confirmed step.
use crate::kotlin_text::{kotlin_trim};

use regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedLogItem {
    pub category: &'static str,
    pub label: &'static str,
    pub detail: String,
    pub icon_emoji: &'static str,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedLogInterpretation {
    pub raw_text: String,
    pub items: Vec<ParsedLogItem>,
    pub follow_up_question: Option<String>,
    pub flow_level_result: Option<&'static str>,
    pub energy_result: Option<&'static str>,
    pub mood_result: Option<&'static str>,
    pub symptoms_result: Vec<&'static str>,
    pub pain_intensity_result: i32,
}

fn negation_stripper() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"\b(no|not|never|without|haven't|hasn't|wasn't|won't|dont|don't|didn't|doesn't|isn't|aren't)\s+(any\s+)?([a-z]+(?:\s+[a-z]+){0,1})")
            .expect("negation regex compiles")
    })
}

pub fn parse(input: &str) -> ParsedLogInterpretation {
    let text = kotlin_trim(input).to_lowercase();
    // Negation strip: a negator cancels only the next one or two words, so
    // "no cramps but severe headache" keeps the headache while
    // "no cramps today and no headache" cancels both. Sections below match
    // against positive_text so negated symptoms are never logged.
    // "no energy" is the one intentional exception: it is an idiom for
    // exhaustion, matched explicitly against the raw text in the energy step.
    let positive_text = negation_stripper().replace_all(&text, " ").to_string();

    let mut items: Vec<ParsedLogItem> = Vec::new();
    let mut symptoms_detected: Vec<&'static str> = Vec::new();
    let mut flow_result: Option<&'static str> = None;
    let mut energy_result: Option<&'static str> = None;
    let mut mood_result: Option<&'static str> = None;
    let mut pain_rating: i32 = 0;
    let mut follow_up: Option<String> = None;

    // 1. Flow / Period (English + Swahili/Sheng: damu, hedhi, mwezi)
    if positive_text.contains("started")
        || positive_text.contains("period")
        || positive_text.contains("bleeding")
        || positive_text.contains("flow")
        || positive_text.contains("damu")
        || positive_text.contains("hedhi")
    {
        let is_heavy = positive_text.contains("heavy")
            || positive_text.contains("soaking")
            || positive_text.contains("lots of blood")
            || positive_text.contains("nyingi");
        let is_light = positive_text.contains("light")
            || positive_text.contains("spotting")
            || positive_text.contains("a little")
            || positive_text.contains("kidogo");
        flow_result = Some(if is_heavy {
            "HEAVY"
        } else if is_light {
            "LIGHT"
        } else {
            "MODERATE"
        });
        items.push(ParsedLogItem {
            category: "Flow",
            label: "Period Active",
            detail: (if is_heavy {
                "Heavy flow"
            } else if is_light {
                "Light flow"
            } else {
                "Moderate flow"
            })
            .to_string(),
            icon_emoji: "🩸",
        });
    } else if positive_text.contains("spotting") {
        flow_result = Some("SPOTTING");
        items.push(ParsedLogItem {
            category: "Flow",
            label: "Spotting",
            detail: "Light spotting noted".to_string(),
            icon_emoji: "🩸",
        });
    }

    // 2. Pain & Cramps (English + Swahili/Sheng: tumbo, maumivu, mbaya)
    let has_cramps = positive_text.contains("cramp")
        || positive_text.contains("stomach is killing")
        || positive_text.contains("stomach hurts")
        || positive_text.contains("pelvic")
        || positive_text.contains("belly hurts")
        || positive_text.contains("tumbo")
        || positive_text.contains("tummy");
    let is_severe_pain = positive_text.contains("killing me")
        || positive_text.contains("unbearable")
        || positive_text.contains("terrible")
        || positive_text.contains("severe")
        || positive_text.contains("mbaya sana")
        || positive_text.contains("kali");
    if has_cramps {
        pain_rating = if is_severe_pain { 8 } else { 4 };
        symptoms_detected.push("cramps");
        items.push(ParsedLogItem {
            category: "Symptom",
            label: "Cramps & Pelvic Pain",
            detail: if is_severe_pain {
                format!("Intense pain (level {pain_rating}/10)")
            } else {
                "Moderate cramps".to_string()
            },
            icon_emoji: "⚡",
        });
        if is_severe_pain {
            follow_up = Some("How long has the pain felt this strong?".to_string());
        }
    }

    // 3. Headache / Migraine (English + Swahili: kichwa)
    if positive_text.contains("headache")
        || positive_text.contains("migraine")
        || positive_text.contains("head hurts")
        || positive_text.contains("head is pounding")
        || positive_text.contains("kichwa")
    {
        let is_migraine = positive_text.contains("migraine")
            || positive_text.contains("pounding")
            || positive_text.contains("kali");
        symptoms_detected.push(if is_migraine { "migraine" } else { "headache" });
        items.push(ParsedLogItem {
            category: "Symptom",
            label: if is_migraine { "Migraine" } else { "Headache" },
            detail: if is_migraine {
                "Intense throbbing".to_string()
            } else {
                "Noticed today".to_string()
            },
            icon_emoji: "🤕",
        });
    }

    // 4. Fatigue / Energy (English + Swahili: nimechoka, uchovu)
    if positive_text.contains("exhausted")
        || positive_text.contains("drained")
        || positive_text.contains("killing me")
        || text.contains("no energy")
        || positive_text.contains("so tired")
        || positive_text.contains("tired")
        || positive_text.contains("sluggish")
        || positive_text.contains("fatigue")
        || positive_text.contains("nimechoka")
        || positive_text.contains("uchovu")
    {
        let is_very_low = positive_text.contains("exhausted")
            || positive_text.contains("drained")
            || text.contains("no energy")
            || positive_text.contains("sana");
        energy_result = Some(if is_very_low { "VERY_LOW" } else { "LOW" });
        symptoms_detected.push("fatigue");
        items.push(ParsedLogItem {
            category: "Energy",
            label: "Low Energy",
            detail: if is_very_low {
                "Feeling drained / exhausted".to_string()
            } else {
                "Feeling tired today".to_string()
            },
            icon_emoji: "🥱",
        });
    } else if positive_text.contains("energetic")
        || positive_text.contains("feeling good")
        || positive_text.contains("high energy")
        || positive_text.contains("nguvu")
    {
        energy_result = Some("GOOD");
        items.push(ParsedLogItem {
            category: "Energy",
            label: "Good Energy",
            detail: "Feeling active & bright".to_string(),
            icon_emoji: "⚡",
        });
    }

    // 5. Mood
    if positive_text.contains("irritable")
        || positive_text.contains("annoyed")
        || positive_text.contains("snappy")
        || positive_text.contains("angry")
        || positive_text.contains("hasira")
    {
        mood_result = Some("UNCOMFORTABLE");
        items.push(ParsedLogItem {
            category: "Mood",
            label: "Irritable / Sensitive",
            detail: "Heightened emotional response".to_string(),
            icon_emoji: "😤",
        });
    } else if positive_text.contains("anxious")
        || positive_text.contains("overwhelmed")
        || positive_text.contains("stressed")
        || positive_text.contains("wasiwasi")
    {
        mood_result = Some("LOW");
        items.push(ParsedLogItem {
            category: "Mood",
            label: "Anxious / Overwhelmed",
            detail: "Feeling nervous or stressed".to_string(),
            icon_emoji: "😰",
        });
    } else if positive_text.contains("sad")
        || positive_text.contains("down")
        || positive_text.contains("crying")
        || positive_text.contains("low mood")
        || positive_text.contains("huzuni")
    {
        mood_result = Some("LOW");
        items.push(ParsedLogItem {
            category: "Mood",
            label: "Low Mood",
            detail: "Feeling down or tender".to_string(),
            icon_emoji: "😔",
        });
    } else if positive_text.contains("happy")
        || positive_text.contains("great")
        || positive_text.contains("calm")
        || positive_text.contains("furaha")
    {
        mood_result = Some("GOOD");
        items.push(ParsedLogItem {
            category: "Mood",
            label: "Positive Mood",
            detail: "Calm and peaceful".to_string(),
            icon_emoji: "😊",
        });
    }

    // 6. Bloating / Nausea / Backache (mgongo)
    if positive_text.contains("bloat") || positive_text.contains("kujaa") {
        symptoms_detected.push("bloating");
        items.push(ParsedLogItem {
            category: "Symptom",
            label: "Bloating",
            detail: "Abdominal fullness".to_string(),
            icon_emoji: "🎈",
        });
    }
    if positive_text.contains("nausea")
        || positive_text.contains("sick to my stomach")
        || positive_text.contains("queasy")
        || positive_text.contains("kichefuchefu")
    {
        symptoms_detected.push("nausea");
        items.push(ParsedLogItem {
            category: "Symptom",
            label: "Nausea",
            detail: "Queasy / sensitive stomach".to_string(),
            icon_emoji: "🤢",
        });
    }
    if positive_text.contains("back")
        || positive_text.contains("lower back")
        || positive_text.contains("mgongo")
    {
        symptoms_detected.push("back_pain");
        items.push(ParsedLogItem {
            category: "Symptom",
            label: "Back Pain",
            detail: "Ache in lower back".to_string(),
            icon_emoji: "🩹",
        });
    }

    // 7. Sleep
    if positive_text.contains("slept badly")
        || positive_text.contains("couldn't sleep")
        || positive_text.contains("insomnia")
        || positive_text.contains("woke up")
        || positive_text.contains("usingizi")
    {
        items.push(ParsedLogItem {
            category: "Sleep",
            label: "Restless Sleep",
            detail: "Interrupted or poor quality".to_string(),
            icon_emoji: "🌙",
        });
    }

    // Fallback for general "something feels off"
    if items.is_empty() {
        let preview: String = input.chars().take(45).collect();
        items.push(ParsedLogItem {
            category: "Wellness",
            label: "General Bodily Shift",
            detail: format!("Possible observation: \"{preview}\""),
            icon_emoji: "✨",
        });
        follow_up = Some("Would you say it's more physical, emotional, or energy related?".to_string());
    }

    ParsedLogInterpretation {
        raw_text: input.to_string(),
        items,
        follow_up_question: follow_up,
        flow_level_result: flow_result,
        energy_result,
        mood_result,
        symptoms_result: symptoms_detected,
        pain_intensity_result: pain_rating,
    }
}
