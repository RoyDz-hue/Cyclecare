//! Port of CycleCareViewModel.completeOnboarding's profile/settings mapping
//! (main@31b88f5, CycleCareViewModel.kt ~1897, CycleRepository.
//! completeOnboardingWithProfile ~191).
//!
//! Scope notes:
//! - The PIN value never enters core. The Kotlin branch `if (pin.isNotBlank())`
//!   is represented by `pin_provided`; secret storage stays host-side.
//! - The health-sync journal (revision + outbox rows via
//!   HealthSyncPayloadCodec) is a local queue write inside the same Room
//!   transaction; it is tracked separately in the coverage map and applied by
//!   the storage layer, not by this pure mapping.
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
    let stored_period_range =
        NaiveDate::parse_from_str(&last_period_date_string, "%Y-%m-%d")
            .ok()
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
