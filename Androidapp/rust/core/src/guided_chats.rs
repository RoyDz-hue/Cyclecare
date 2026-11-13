//! Guided Community Circles: offline repository semantics.
//! Port of data/economy/GuidedChatsRepository.kt (main@31b88f5): the fallback
//! channel directory (verbatim), message validation, and the in-memory
//! message store. Server-backed circles (chat_topics/chat_messages), live
//! status, and Community Guide personas are network seams; the offline path
//! is a complete port.
//!
//! Production honesty rules preserved: offline circles start EMPTY (no
//! fabricated member posts); messages written offline stay on device, as the
//! user's own words ("You", isSelf = true).

use crate::kotlin_text::kotlin_trim;

/// GuidedChatChannel (EconomyModels.kt). minTier is FREE for every fallback
/// channel; server circles are FREE as well at main@31b88f5.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GuidedChatChannel {
    pub id: &'static str,
    pub title: &'static str,
    pub topic_guide: &'static str,
}

/// The built-in sample circles shown when cloud topics cannot be loaded,
/// verbatim from GuidedChatsRepository.fallbackChannels.
pub const FALLBACK_CHANNELS: &[GuidedChatChannel] = &[
    GuidedChatChannel {
        id: "circle_luteal_rest",
        title: "Luteal Phase Softness & Reflection",
        topic_guide: "How might you create space for rest when your energy turns inward?",
    },
    GuidedChatChannel {
        id: "circle_ovulation_vitality",
        title: "Creativity & Mid-Cycle Energy",
        topic_guide: "What activities feel meaningful when your energy is higher?",
    },
    GuidedChatChannel {
        id: "circle_sovereign_sanctuary",
        title: "Reflection & Wellbeing",
        topic_guide: "What helps you feel supported in your daily wellbeing journey?",
    },
];

/// GuidedChatMessage (offline fields only; remoteId/personaId are server seams).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GuidedChatMessage {
    pub channel_id: String,
    pub sender_alias: String,
    pub content: String,
    pub is_self: bool,
}

/// Kotlin String.length: UTF-16 code units (a supplementary-plane char is 2).
fn kotlin_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// postMessage validation (verbatim failure messages, in order):
/// trim -> empty -> "Message cannot be empty"; > 500 UTF-16 units ->
/// "Message exceeds 500 characters".
pub fn validate_message(content: &str) -> Result<String, &'static str> {
    let trimmed = kotlin_trim(content).to_string();
    if trimmed.is_empty() {
        return Err("Message cannot be empty");
    }
    if kotlin_len(&trimmed) > 500 {
        return Err("Message exceeds 500 characters");
    }
    Ok(trimmed)
}

/// Composer onValueChange cap: accepts the new draft iff it is at most 500
/// UTF-16 units (`if (it.length <= 500) draftText = it`).
pub fn composer_accepts(draft: &str) -> bool {
    kotlin_len(draft) <= 500
}

/// postMessage success path: the stored message (alias "You", isSelf).
pub fn post_message(channel_id: &str, content: &str) -> Result<GuidedChatMessage, &'static str> {
    let trimmed = validate_message(content)?;
    Ok(GuidedChatMessage {
        channel_id: channel_id.to_string(),
        sender_alias: "You".to_string(),
        content: trimmed,
        is_self: true,
    })
}

/// Empty-circle copy for the offline fallback channels, keyed by the last
/// live-refresh status (CircleServiceStatus). Verbatim messages.
pub fn offline_empty_copy(status: CircleServiceStatus) -> &'static str {
    match status {
        CircleServiceStatus::SignInUnconfirmed => "The live circle needs a confirmed sign-in, and yours could not be confirmed. Sign in again to continue. Reflections you write stay on this device.",
        CircleServiceStatus::Network => "The live circle could not be reached. Check your connection and try again. Reflections you write stay on this device.",
        CircleServiceStatus::Unavailable => "The live circle is not available right now. Reflections you write stay on this device; please try again in a little while.",
    }
}

/// CommunityGuidesService.CircleServiceStatus (subset used by the screen).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CircleServiceStatus {
    SignInUnconfirmed,
    Network,
    Unavailable,
}
