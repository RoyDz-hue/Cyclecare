//! Port of the decision logic in `CycleCareViewModel.consultCare`
//! (:884-947, ref 31b88f5). The orchestration itself (coroutine launch,
//! CarePrivacyService.updateConsents, LayeredChatOrchestrator.processRawChat)
//! stays host glue; this module locks the gates, windows and literals.
//! Line citations grep-verified against main 31b88f5.
//!
//! JVM corpus: pending next bundle (verbatim-copy harness, owner-executed);
//! until then the functions carry source-read evidence only, no parity claim.

use crate::care_ai::is_remote_care_response;
use crate::kotlin_text::{kotlin_trim, take_utf16};

/// :885-886 entry gate. Returns the trimmed question, or None when the source
/// returns early: blank after Kotlin trim, UTF-16 length > 1200, or already
/// loading. Kotlin String.length counts UTF-16 code units, so astral
/// characters (e.g. emoji) count double — utf16_len, not chars().count().
pub fn consult_entry(question: &str, is_care_loading: bool) -> Option<String> {
    let clean = kotlin_trim(question);
    if clean.is_empty() || utf16_len(clean) > 1200 || is_care_loading {
        return None;
    }
    Some(clean.to_string())
}

/// Kotlin String.length: UTF-16 code units.
pub fn utf16_len(s: &str) -> usize {
    s.chars().map(|c| c.len_utf16()).sum()
}

/// :893-894 remoteAllowed. Safety and local help are never blocked by
/// connectivity, consent, restrictions or tier; this gate only controls the
/// REMOTE path.
pub fn remote_allowed(
    is_ai_sharing_allowed: bool,
    is_online: bool,
    is_banned: bool,
    is_suspended: bool,
    is_red_flag: bool,
) -> bool {
    is_ai_sharing_allowed && is_online && !is_banned && !is_suspended && !is_red_flag
}

/// :897-901 contextSummary is compiled only when includeFullContext and
/// remote are allowed and the input is not a red flag. The isRedFlag arm is
/// redundant given remote_allowed includes it — ported 1:1 as written.
pub fn wants_context_summary(
    include_full_context: bool,
    remote_allowed: bool,
    is_red_flag: bool,
) -> bool {
    include_full_context && remote_allowed && !is_red_flag
}

/// :907-909 isCurrentCareRequest (LOCAL fun): owner, session and request
/// generation must all still match.
pub fn is_current_care_request(
    owner: &str,
    session: i64,
    request_generation: i64,
    current_owner: &str,
    current_session: i64,
    current_generation: i64,
) -> bool {
    owner == current_owner && session == current_session && request_generation == current_generation
}

/// :919 accessToken forwarded only when consent is ready and sharing allowed.
pub fn access_token_for_request<'a>(
    consent_ready: bool,
    is_ai_sharing_allowed: bool,
    access_token: Option<&'a str>,
) -> Option<&'a str> {
    if consent_ready && is_ai_sharing_allowed {
        access_token
    } else {
        None
    }
}

/// :920 forceAi.
pub fn force_ai(include_full_context: bool, context_summary_present: bool) -> bool {
    include_full_context && context_summary_present
}

/// :921 allowRemoteAi.
pub fn allow_remote_ai(consent_ready: bool, is_ai_sharing_allowed: bool) -> bool {
    consent_ready && is_ai_sharing_allowed
}

/// One chat-history turn as the window consumes it (:922-925).
pub struct CareHistoryTurn<'a> {
    pub source: &'a str,
    pub user_query: &'a str,
    pub summary: &'a str,
    pub what_text: &'a str,
}

/// :922-925 conversation history window: only remote-sourced responses, the
/// last 3 turns, each flattened to a user message (query taken to 600 UTF-16
/// units) and an assistant message (summary + " " + whatText, taken to 600).
/// Returns (role, text) pairs in source order. Empty when consent is not
/// ready.
pub fn history_window(consent_ready: bool, history: &[CareHistoryTurn]) -> Vec<(String, String)> {
    if !consent_ready {
        return Vec::new();
    }
    let remote: Vec<&CareHistoryTurn> = history
        .iter()
        .filter(|t| is_remote_care_response(t.source))
        .collect();
    let start = remote.len().saturating_sub(3);
    let mut out = Vec::new();
    for t in &remote[start..] {
        out.push(("user".to_string(), take_utf16(t.user_query, 600)));
        out.push((
            "assistant".to_string(),
            take_utf16(&format!("{} {}", t.summary, t.what_text), 600),
        ));
    }
    out
}

/// :931-933 detectedItemsSummary: interpretation items joined with " · ",
/// absent when there is no proposal/interpretation.
pub fn detected_items_summary(labels: Option<&[String]>) -> Option<String> {
    labels.map(|ls| ls.join(" \u{b7} "))
}

/// :933 history append keeps the last 24 turns.
pub const CARE_HISTORY_KEEP: usize = 24;

/// :940-941 failure message when the request throws (non-cancellation) and is
/// still current.
pub const CARE_FAILURE_MESSAGE: &str =
    "This explanation couldn't finish. Your health log has not changed.";
