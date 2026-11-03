//! Port of the care-log draft machine in CycleCareViewModel
//! (editCareLog :856-860, cancelCareLog :862-866, confirmCareLog :868-882,
//! main 31b88f5). The consent-gate delegate (careLogConsentGate) and the
//! repository writes stay host glue; the null-guards, state clears, owner
//! rechecks and the saved-message literal are ported.

/// :878 saved message after merge+save.
pub const CARE_LOG_SAVED_MESSAGE: &str =
    "Saved to your health log. No coins were awarded for this health entry.";

/// Draft state: whether a proposal is pending (source stores the whole
/// proposal; only presence matters to the state machine).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CareLogDraft {
    pub pending: bool,
}

/// :856-860 editCareLog always (re)proposes and shows the interpretation.
pub fn edit_proposed(proposal_present: bool) -> CareLogDraft {
    // :858-859 pendingCareLog = proposal?.interpretation: a NULL proposal
    // leaves the pending draft UNSET (not pending).
    CareLogDraft { pending: proposal_present }
}

/// :862-866 cancelCareLog clears the draft and turn id.
pub fn cancelled() -> CareLogDraft {
    CareLogDraft { pending: false }
}

/// Outcome of confirmCareLog (:868-882).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmOutcome {
    /// :869 no pending proposal; source returns silently, state untouched.
    NoPending,
    /// :870 the gate's confirm returned null; source returns silently with
    /// the draft still pending.
    ConfirmRejected,
    /// Draft cleared; host proceeds to the async merge/save phase.
    Cleared,
}

pub fn confirm(draft: CareLogDraft, gate_confirmed: bool) -> ConfirmOutcome {
    if !draft.pending {
        return ConfirmOutcome::NoPending;
    }
    if !gate_confirmed {
        return ConfirmOutcome::ConfirmRejected;
    }
    ConfirmOutcome::Cleared
}

/// :874/:876 owner rechecks around the existing-log read: the confirmed
/// proposal's owner must still be the effective user at BOTH checkpoints.
pub fn confirmed_owner_still_current(confirmed_owner: &str, current_owner: &str) -> bool {
    confirmed_owner == current_owner
}

#[cfg(test)]
mod null_proposal_tests {
    use super::*;

    #[test]
    fn null_proposal_leaves_draft_unset() {
        // :858-859 proposal?.interpretation: propose() returned null ->
        // pendingCareLog stays unset; confirm() then hits :869 NoPending.
        let d = edit_proposed(false);
        assert!(!d.pending);
        assert_eq!(format!("{:?}", confirm(d, true)), "NoPending");
    }

    #[test]
    fn present_proposal_marks_pending() {
        let d = edit_proposed(true);
        assert!(d.pending);
        assert_eq!(format!("{:?}", confirm(d, true)), "Cleared");
    }
}

// ---------------------------------------------------------------------------
// confirmCareLog (:866-882) + CareConversationPolicy.merge (:58-66).
// ---------------------------------------------------------------------------

use crate::kotlin_text::kotlin_is_blank;

/// The five fields merge() touches (CycleDayLog subset).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergedLogFields {
    pub flow_level: String,
    pub energy_level: String,
    pub mood_level: String,
    pub symptoms_csv: String,
    pub pain_intensity: i32,
}

/// ParsedLogInterpretation decision fields (:204-212).
pub struct ParsedInterpretation {
    pub flow_level_result: Option<String>,
    pub energy_result: Option<String>,
    pub mood_result: Option<String>,
    pub symptoms_result: Vec<String>,
    pub pain_intensity_result: i32,
}

/// Kotlin trim() on this toolchain strips NBSP too (empirically probed,
/// jdk-17.0.21+9 + kotlinc 2.1.0): char-wise whitespace per Kotlin's
/// isWhitespace, matching kotlin_text's established helper semantics.
fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c.is_whitespace() || c == '\u{00A0}')
}

/// CareConversationPolicy.merge (:58-66) verbatim: null results fall back to
/// the existing field; symptomsCsv = (existing.split(",") + parsed).map(trim)
/// .filter(isNotBlank).distinct().joinToString(","); pain: parsed > 0 ->
/// coerceIn(0,10), else keep existing. The raw conversation is intentionally
/// NOT copied into permanent health notes (source comment).
pub fn merge_log(existing: &MergedLogFields, parsed: &ParsedInterpretation) -> MergedLogFields {
    let mut symptoms: Vec<String> = existing
        .symptoms_csv
        .split(',')
        .chain(parsed.symptoms_result.iter().map(|s| s.as_str()))
        .map(kotlin_trim)
        .filter(|s| !kotlin_is_blank(s))
        .map(|s| s.to_string())
        .collect();
    // Kotlin distinct(): ALL duplicates removed, first-occurrence order kept.
    let mut seen = std::collections::HashSet::new();
    symptoms.retain(|s| seen.insert(s.clone()));
    MergedLogFields {
        flow_level: parsed.flow_level_result.clone().unwrap_or_else(|| existing.flow_level.clone()),
        energy_level: parsed.energy_result.clone().unwrap_or_else(|| existing.energy_level.clone()),
        mood_level: parsed.mood_result.clone().unwrap_or_else(|| existing.mood_level.clone()),
        symptoms_csv: symptoms.join(","),
        pain_intensity: if parsed.pain_intensity_result > 0 {
            parsed.pain_intensity_result.clamp(0, 10)
        } else {
            existing.pain_intensity
        },
    }
}

/// Host effects for the confirmCareLog launch body (:871-881).
pub trait ConfirmEffects {
    fn effective_user_id(&self) -> &str;
    /// :873 repository.getLogForDate(date); None -> CycleDayLog(date) default.
    fn get_log_for_date(&mut self, date: &str) -> Option<MergedLogFields>;
    /// :875 repository.saveLog(merge(existing, interpretation)).
    fn save_log(&mut self, merged: &MergedLogFields);
    /// :876 careSavedReviewMessage = CARE_LOG_SAVED_MESSAGE.
    fn show_saved_message(&mut self);
    /// :877
    fn update_calculations(&mut self);
    /// :878
    fn auto_sync_silently(&mut self);
}

/// :871-881 composed: owner recheck BEFORE the read AND BEFORE the save;
/// save -> message -> updateCalculations -> autoSyncSilently in source order.
/// Returns false when an owner check aborted the body (silently, as source).
pub fn confirm_care_log_body<E: ConfirmEffects>(
    fx: &mut E,
    confirmed_owner: &str,
    date: &str,
    interpretation: &ParsedInterpretation,
) -> bool {
    if confirmed_owner != fx.effective_user_id() {
        return false; // :872
    }
    let existing = fx
        .get_log_for_date(date)
        .unwrap_or(MergedLogFields {
            flow_level: String::new(),
            energy_level: String::new(),
            mood_level: String::new(),
            symptoms_csv: String::new(),
            pain_intensity: 0,
        }); // :873 ?: default; NOTE: the source default CycleDayLog carries
            // ovulationTest="NONE" etc. - only the five merged fields are
            // modeled here; the host writes the full row.
    if confirmed_owner != fx.effective_user_id() {
        return false; // :874
    }
    let merged = merge_log(&existing, interpretation);
    fx.save_log(&merged); // :875
    fx.show_saved_message(); // :876
    fx.update_calculations(); // :877
    fx.auto_sync_silently(); // :878
    true
}
