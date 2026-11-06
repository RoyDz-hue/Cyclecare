//! Decision-core port of ui/screens/CareScreen.kt (ref 31b88f5): assistant
//! detail assembly (:165-167), send gates (:117 and :206-209 - TWO DISTINCT
//! gates, see quirk note), draft items text (:183), consent-card manifest/
//! provider text (:303-317, :324-326, :342), premiumOrAdmin (:116), style
//! tab label (:231), UTF-16 take(1200) caps (:187, :201). Compose, scrolling,
//! dialogs and the actual consult/image actions stay Android seams; the
//! consent Allow action stays under the account-mutation boundary.
//!
//! QUIRK (ported 1:1, labeled): the composer's live `canSend` (:117) checks
//! queryText.LENGTH (RAW, untrimmed) while the actual onSend gate (:208)
//! checks question.length AFTER trim - a raw string of 1200 spaces plus one
//! letter fails canSend but would pass onSend, and a 1201-char raw string
//! that trims to <=1200 passes canSend=false either way (canSend raw length
//! dominates). Both gates ported separately, never merged.

use crate::kotlin_text::{kotlin_trim, take_utf16};

/// :165-167 assistantDetail: filter blank, Kotlin distinct() (first
/// occurrence wins, order preserved), join with a blank line.
pub fn assistant_detail(texts: [&str; 5]) -> String {
    let mut seen: Vec<&str> = Vec::new();
    for t in texts {
        if !kotlin_trim(t).is_empty() && !seen.contains(&t) {
            seen.push(t);
        }
    }
    seen.join("\n\n")
}

/// :117 composer live gate. NOTE the RAW length check (quirk, see module doc).
pub fn can_send(query_text: &str, is_care_loading: bool) -> bool {
    !kotlin_trim(query_text).is_empty()
        && !is_care_loading
        && query_text.chars().map(|c| c.len_utf16()).sum::<usize>() <= 1200
}

/// :206-209 onSend gate: trims FIRST, then checks the trimmed text.
/// Returns the trimmed question when the source would send, else None.
pub fn on_send_question(query_text: &str, is_care_loading: bool) -> Option<String> {
    let question = kotlin_trim(query_text).to_string();
    if !question.is_empty()
        && !is_care_loading
        && question.chars().map(|c| c.len_utf16()).sum::<usize>() <= 1200
    {
        Some(question)
    } else {
        None
    }
}

/// :183 draft items text: "label: detail" joined by " \u{b7} ".
pub fn draft_items_text(items: &[(String, String)]) -> String {
    items
        .iter()
        .map(|(label, detail)| format!("{label}: {detail}"))
        .collect::<Vec<_>>()
        .join(" \u{b7} ")
}

/// :187 / :201 UTF-16 take(1200) cap for composer and draft edits.
pub fn cap_1200(text: &str) -> String {
    take_utf16(text, 1200)
}

/// Manifest card state (:303-306).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ManifestState {
    /// manifest == null && isLoadingAiProcessorManifest
    Checking,
    /// manifest == null && !isLoadingAiProcessorManifest
    Unavailable,
    /// manifest != null
    Ready,
}

pub fn manifest_state(manifest_present: bool, is_loading_manifest: bool) -> ManifestState {
    if !manifest_present && is_loading_manifest {
        ManifestState::Checking
    } else if !manifest_present {
        ManifestState::Unavailable
    } else {
        ManifestState::Ready
    }
}

/// :303-306 header line for each state, verbatim strings (ellipsis U+2026).
pub fn manifest_state_text(state: ManifestState) -> &'static str {
    match state {
        ManifestState::Checking => "Checking who can answer\u{2026}",
        ManifestState::Unavailable => "Online answers are unavailable right now. Nothing was enabled.",
        ManifestState::Ready => "Who answers you online:",
    }
}

/// :309-317 one provider line. Eligible+consentEnabled providers show the
/// model and optional processors; others show unavailable + optional reason.
pub fn provider_line(
    display_name: &str,
    model: &str,
    eligible: bool,
    consent_enabled: bool,
    processors: &[String],
    unavailable_reason: &str,
) -> String {
    if eligible && consent_enabled {
        let mut line = format!("\u{2022} {display_name} ({model})");
        if !processors.is_empty() {
            line.push_str(" - via ");
            line.push_str(&processors.join(", "));
        }
        line
    } else {
        let mut line = format!("\u{2022} {display_name} - unavailable");
        // Kotlin isNotBlank: all-whitespace reasons get NO suffix.
        if !kotlin_trim(unavailable_reason).is_empty() {
            line.push_str(": ");
            line.push_str(unavailable_reason);
        }
        line
    }
}

/// :324-326 policy-details provider filter.
pub fn policy_provider_visible(eligible: bool, consent_enabled: bool) -> bool {
    eligible && consent_enabled
}

/// :329 policy link label.
pub fn policy_label(processor_name: &str) -> String {
    format!("{processor_name} privacy policy")
}

/// :342 Allow-button gate.
pub fn allow_enabled(manifest_present: bool) -> bool {
    manifest_present
}

/// :116 image-generation gate.
pub fn premium_or_admin(is_admin: bool, tier_is_premium: bool) -> bool {
    is_admin || tier_is_premium
}

/// :231 style tab label (bullet U+2022 when selected).
pub fn style_tab_label(selected: bool, title: &str) -> String {
    if selected {
        format!("\u{2022} {title}")
    } else {
        title.to_string()
    }
}
