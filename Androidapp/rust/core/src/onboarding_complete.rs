//! Port of CycleCareViewModel.completeOnboarding's profile/settings mapping
//! (main@31b88f5, CycleCareViewModel.kt ~1897, CycleRepository.
//! completeOnboardingWithProfile ~191).
//!
//! Scope notes:
//! - The PIN value never enters core. The Kotlin branch `if (pin.isNotBlank())`
//!   is represented by `pin_provided`; secret storage stays host-side.
//! - WARNING (per main 2026-11-02): the health-sync journal (revision +
//!   outbox rows via HealthSyncPayloadCodec) is part of the same Room
//!   transaction in production and is NOT yet ported, so this mapping alone
//!   is NOT whole-production-transaction parity. Keep this caveat attached
//!   to any completion claim until the journal is ported.
//! - org.json key order is INSERTION order (AOSP LinkedHashMap, verified in
//!   sync_codec); the dynamic-insights JSON preserves answer insertion order
//!   and is rendered through the shared AOSP-faithful writer.

use chrono::NaiveDate;

use crate::onboarding_vm::OnboardingCompletionParams;
use crate::sync_codec::{json_value_to_string, CycleSettings, JsonValue, OnboardingProfile};

/// Fields completeOnboardingWithProfile copies onto the current CycleSettings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsPatch {
    pub is_onboarding_completed: bool,
    pub selected_goals_csv: String,
    pub last_period_start_date: String,
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub remind_fertile: bool,
    pub is_app_lock_enabled: bool,
}

impl SettingsPatch {
    pub fn apply_to(&self, settings: &mut CycleSettings) {
        settings.is_onboarding_completed = self.is_onboarding_completed;
        settings.selected_goals_csv = self.selected_goals_csv.clone();
        settings.last_period_start_date = self.last_period_start_date.clone();
        settings.average_cycle_length = self.average_cycle_length;
        settings.period_duration = self.period_duration;
        settings.remind_fertile = self.remind_fertile;
        settings.is_app_lock_enabled = self.is_app_lock_enabled;
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct CompletionOutcome {
    pub profile: OnboardingProfile,
    pub settings_patch: SettingsPatch,
    /// The repository's in-memory storedPeriodDateRange after completion:
    /// start .. start + coerceIn(periodDurationDays, 2, 10) - 1, or None when
    /// the profile carries no parseable start date.
    pub stored_period_range: Option<(NaiveDate, NaiveDate)>,
    /// authPreferences.onboardingDynamicInsightsJson (local-only).
    pub dynamic_insights_json: Option<String>,
    /// Kotlin `pin.isNotBlank()` branch outcomes (secret value never seen).
    pub pin_stored: bool,
    pub quick_unlock_asked_set: bool,
    pub is_cloud_sync_allowed: bool,
}

/// Verbatim field mapping of completeOnboarding + the profile insert and
/// settings copy in completeOnboardingWithProfile.
pub fn complete_onboarding(
    params: &OnboardingCompletionParams,
    account_mode: &str,
    now_millis: i64,
    pin_provided: bool,
    enable_lock: bool,
) -> CompletionOutcome {
    let last_period_date_string = if params.period_start_choice == "UNKNOWN" {
        String::new()
    } else {
        params.last_period_date.format("%Y-%m-%d").to_string()
    };
    let profile = OnboardingProfile {
        id: 1,
        is_completed: true,
        completed_at_timestamp: now_millis,
        is_age_verified: true,
        is_terms_agreed: true,
        account_mode: account_mode.to_string(),
        selected_goals_csv: params.goals.join(","),
        last_period_date_string: last_period_date_string.clone(),
        last_period_end_date_string: params
            .last_period_end_date
            .map(|d| d.format("%Y-%m-%d").to_string())
            .unwrap_or_default(),
        approximate_period_choice: params.period_start_choice.clone(),
        cycle_length_option: if params.cycle_length_option.is_empty() {
            "UNKNOWN".to_string()
        } else {
            params.cycle_length_option.clone()
        },
        cycle_length_days: params.cycle_length_days as i32,
        period_duration_days: params.period_duration_days as i32,
        remind_fertile_window: params.remind_fertile,
        typical_symptoms_csv: params.typical_symptoms.join(","),
        typical_energy: params.typical_energy.clone(),
        typical_sleep_quality: params.typical_sleep.clone(),
        has_completed_deep_baseline: params.has_completed_deep_baseline,
    };
    let settings_patch = SettingsPatch {
        is_onboarding_completed: true,
        selected_goals_csv: profile.selected_goals_csv.clone(),
        last_period_start_date: profile.last_period_date_string.clone(),
        average_cycle_length: profile.cycle_length_days,
        period_duration: profile.period_duration_days,
        remind_fertile: profile.remind_fertile_window,
        is_app_lock_enabled: enable_lock,
    };
    // Strict LocalDate.parse semantics (probe-locked port), never chrono's
    // lenient %Y-%m-%d: this string is also an external entry point when a
    // profile row is re-read from storage.
    let stored_period_range =
        crate::cycle_math::parse_local_date_iso(&last_period_date_string)
            .map(|start| {
                let pd = params.period_duration_days.clamp(2, 10);
                (start, start + chrono::Duration::days(pd - 1))
            });
    let dynamic_insights_json = if params.dynamic_answers.is_empty() {
        None
    } else {
        let pairs: Vec<(String, JsonValue)> = params
            .dynamic_answers
            .iter()
            .map(|(id, answers)| {
                (
                    id.clone(),
                    JsonValue::Array(
                        answers.iter().map(|a| JsonValue::Str(a.clone())).collect(),
                    ),
                )
            })
            .collect();
        Some(json_value_to_string(&JsonValue::Object(pairs)))
    };
    CompletionOutcome {
        profile,
        settings_patch,
        stored_period_range,
        dynamic_insights_json,
        pin_stored: pin_provided,
        quick_unlock_asked_set: pin_provided,
        is_cloud_sync_allowed: params.is_cloud_sync_allowed,
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    fn base_params() -> OnboardingCompletionParams {
        OnboardingCompletionParams {
            goals: vec!["Track my periods".to_string()],
            last_period_date: NaiveDate::from_ymd_opt(2026, 10, 29).unwrap(),
            last_period_end_date: None,
            period_start_choice: "APPROXIMATE:A few days ago".to_string(),
            cycle_length_days: 28,
            cycle_length_option: "28 days".to_string(),
            period_duration_days: 4,
            remind_fertile: true,
            typical_symptoms: vec!["Cramps".to_string(), "Bloating".to_string()],
            typical_energy: String::new(),
            typical_sleep: String::new(),
            has_completed_deep_baseline: true,
            is_cloud_sync_allowed: false,
            dynamic_answers: vec![],
        }
    }

    #[test]
    fn unknown_choice_blanks_the_start_date() {
        let mut p = base_params();
        p.period_start_choice = "UNKNOWN".to_string();
        let out = complete_onboarding(&p, "PRIVATE", 1_762_000_000_000, false, false);
        assert_eq!(out.profile.last_period_date_string, "");
        assert!(out.stored_period_range.is_none());
        assert_eq!(out.settings_patch.last_period_start_date, "");
    }

    #[test]
    fn blank_cycle_option_becomes_unknown_and_duration_is_clamped_in_range() {
        let mut p = base_params();
        p.cycle_length_option = String::new();
        p.period_duration_days = 1;
        let out = complete_onboarding(&p, "PRIVATE", 0, false, false);
        assert_eq!(out.profile.cycle_length_option, "UNKNOWN");
        // coerceIn(2, 10): stored range end = start + 1, but the profile keeps 1.
        assert_eq!(out.profile.period_duration_days, 1);
        assert_eq!(
            out.stored_period_range,
            Some((
                NaiveDate::from_ymd_opt(2026, 10, 29).unwrap(),
                NaiveDate::from_ymd_opt(2026, 10, 30).unwrap()
            ))
        );
    }

    #[test]
    fn dynamic_answers_serialize_as_org_json_object() {
        let mut p = base_params();
        p.dynamic_answers = vec![
            ("q1".to_string(), vec!["a".to_string(), "b".to_string()]),
            ("q2".to_string(), vec!["x".to_string()]),
        ];
        let out = complete_onboarding(&p, "PRIVATE", 0, false, false);
        assert_eq!(
            out.dynamic_insights_json.as_deref(),
            Some("{\"q1\":[\"a\",\"b\"],\"q2\":[\"x\"]}")
        );
    }
}

// ---------- Health-sync journal (CycleRepository.completeOnboardingWithProfile
// transaction write-set, main@31b88f5 ~L191-236) ----------
//
// Production writes, inside ONE Room transaction: insert profile, save the
// patched settings, put a revision row and enqueue an outbox row for BOTH
// "profile" and "settings". Owner is lifecycleIdentity.orBlank->legacy_guest;
// operationIds are random UUIDs and timestamps are the wall clock, so both
// are caller-supplied here to keep core deterministic.

use crate::sync_codec::{
    self, HealthSyncOutboxEntry, HealthSyncRevision,
};

/// `lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"`.
/// Kotlin isBlank: every char is whitespace by Char.isWhitespace, which
/// (unlike Rust) also counts U+00A0, U+2007 and U+202F - use the probe-locked
/// kotlin_text helper, never str::trim or is_empty.
pub fn owner_or_legacy(identity: Option<&str>) -> String {
    match identity {
        Some(s) if !s.chars().all(crate::kotlin_text::kotlin_is_whitespace) => s.to_string(),
        _ => "legacy_guest".to_string(),
    }
}

/// The full journal write-set for one completion, planned pure.
#[derive(Debug, Clone, PartialEq)]
pub struct JournalWriteSet {
    pub owner: String,
    pub updated_settings: CycleSettings,
    pub profile_revision: HealthSyncRevision,
    pub profile_outbox: HealthSyncOutboxEntry,
    pub settings_revision: HealthSyncRevision,
    pub settings_outbox: HealthSyncOutboxEntry,
}

/// Verbatim write-set arithmetic of the production transaction:
/// revision = (prior?.revision ?: 0) + 1; the outbox baseRevision and the
/// revision row's serverRevision both carry prior?.serverRevision ?: 0.
pub fn plan_completion_journal(
    outcome: &CompletionOutcome,
    current_settings: &CycleSettings,
    owner_identity: Option<&str>,
    consent_generation: i64,
    now_millis: i64,
    profile_operation_id: &str,
    settings_operation_id: &str,
    prior_profile_revision: Option<&HealthSyncRevision>,
    prior_settings_revision: Option<&HealthSyncRevision>,
) -> JournalWriteSet {
    let owner = owner_or_legacy(owner_identity);
    let mut updated_settings = current_settings.clone();
    outcome.settings_patch.apply_to(&mut updated_settings);

    let profile_payload = sync_codec::profile_payload(&outcome.profile);
    let settings_payload = sync_codec::settings_payload(&updated_settings);

    let mk = |record_id: &str,
              operation_id: &str,
              payload: String,
              prior: Option<&HealthSyncRevision>| {
        let revision = prior.map(|p| p.revision).unwrap_or(0) + 1;
        let server_revision = prior.map(|p| p.server_revision).unwrap_or(0);
        let hash = sync_codec::hash(Some(&payload));
        (
            HealthSyncRevision {
                owner_id: owner.clone(),
                record_id: record_id.to_string(),
                revision,
                payload_hash: Some(hash.clone()),
                tombstone: false,
                updated_at: now_millis,
                server_revision,
            },
            HealthSyncOutboxEntry {
                owner_id: owner.clone(),
                operation_id: operation_id.to_string(),
                record_id: record_id.to_string(),
                kind: "UPSERT".to_string(),
                base_revision: server_revision,
                revision,
                payload_json: Some(payload),
                payload_hash: hash,
                consent_generation,
                attempts: 0,
                next_attempt_at: 0,
                last_error: None,
                server_receipt: None,
                server_revision: None,
            },
        )
    };
    let (profile_revision, profile_outbox) =
        mk("profile", profile_operation_id, profile_payload, prior_profile_revision);
    let (settings_revision, settings_outbox) = mk(
        "settings",
        settings_operation_id,
        settings_payload,
        prior_settings_revision,
    );
    JournalWriteSet {
        owner,
        updated_settings,
        profile_revision,
        profile_outbox,
        settings_revision,
        settings_outbox,
    }
}
