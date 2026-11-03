//! Decision-core port of ui/screens/SomethingDifferentSheet.kt (ref 31b88f5):
//! parse triggers (:152-154, :164), the unpack domain tables (:189-195 chips
//! and :229-235 sub-option when-chain), quick chips (:70-81), the sensation
//! append rule (:248, :280), interpretation card gate (:292), item line
//! (:320) and follow-up text (:345). The parser itself
//! (CycleCareViewModel.parseNaturalLanguageInput -> already-ported engine),
//! sheet/Compose rendering and confirmParsedLog stay seams.
//!
//! QUIRK (ported 1:1, labeled): the domain->suboptions table is written TWICE
//! in the source (chips literal :189-195 and when-chain :229-235) as separate
//! literals; both are ported as separate tables so any source drift stays
//! visible instead of being silently unified.

/// :189-195 domain chips literal, verbatim.
pub const UNPACK_DOMAIN_CHIPS: [(&str, &[&str]); 5] = [
    ("Physical", &["Cramps", "Headache", "Back pain", "Breast tenderness"]),
    ("Mood", &["Anxious", "Irritable", "Tearful", "Low motivation"]),
    ("Energy & Sleep", &["Heavy fatigue", "Brain fog", "Restless sleep"]),
    ("Pelvic", &["Discharge change", "Spotting", "Deep pressure"]),
    ("Stomach", &["Bloating", "Nausea", "Appetite surge"]),
];

/// :229-235 sub-option when-chain (second, separate literal; else -> []).
pub fn unpack_sub_options(domain: &str) -> &'static [&'static str] {
    match domain {
        "Physical" => &["Cramps", "Headache", "Back pain", "Breast tenderness"],
        "Mood" => &["Anxious", "Irritable", "Tearful", "Low motivation"],
        "Energy & Sleep" => &["Heavy fatigue", "Brain fog", "Restless sleep"],
        "Pelvic" => &["Discharge change", "Spotting", "Deep pressure"],
        "Stomach" => &["Bloating", "Nausea", "Appetite surge"],
        _ => &[],
    }
}

/// :70-81 quick chips, verbatim order.
pub const QUICK_CHIPS: [&str; 10] = [
    "Cramps",
    "Fatigue",
    "Headache",
    "Mood shift",
    "Stomach pain",
    "Bloating",
    "Nausea",
    "Restless sleep",
    "Spotting",
    "Just feel off",
];

/// :153 / :164 parse trigger: only non-blank input parses.
pub fn should_parse(text: &str) -> bool {
    !crate::kotlin_text::kotlin_trim(text).is_empty()
}

/// :248 / :280 sensation append: blank input becomes the item, otherwise
/// ", "-appended. The source then re-parses the whole text.
pub fn append_sensation(text_input: &str, item: &str) -> String {
    if crate::kotlin_text::kotlin_trim(text_input).is_empty() {
        item.to_string()
    } else {
        format!("{text_input}, {item}")
    }
}

/// :292 interpretation card gate.
pub fn interpretation_card_visible(interpretation_present: bool, items_count: usize) -> bool {
    interpretation_present && items_count > 0
}

/// :320 one interpreted-item line.
pub fn interpretation_item_line(label: &str, detail: &str) -> String {
    format!("\u{2022} {label} ({detail})")
}

/// :345 follow-up question line (U+1F4AD).
pub fn follow_up_text(question: &str) -> String {
    format!("\u{1F4AD} {question}")
}
