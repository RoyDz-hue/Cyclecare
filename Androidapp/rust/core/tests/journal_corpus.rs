//! JVM-parity corpus for the completion journal write-set arithmetic
//! (CycleRepository.completeOnboardingWithProfile transaction, main@31b88f5).
//! fixtures/journal_corpus.jsonl generated 2026-11-02 by compiling the
//! verbatim transaction sequence over an in-memory fake HealthSyncDao
//! (kotlinc/JVM). Payload CONTENT is excluded here on purpose: it is locked
//! by the sync_codec corpus; this locks owner resolution, revision
//! increments, serverRevision carryover, baseRevision, kind and consent.

use cyclecare_core::onboarding_complete::{
    complete_onboarding, owner_or_legacy, plan_completion_journal,
};
use cyclecare_core::onboarding_vm::OnboardingCompletionParams;
use cyclecare_core::sync_codec::{CycleSettings, HealthSyncRevision};

fn params() -> OnboardingCompletionParams {
    OnboardingCompletionParams {
        goals: vec!["g".to_string()],
        last_period_date: chrono::NaiveDate::from_ymd_opt(2026, 10, 29).unwrap(),
        last_period_end_date: None,
        period_start_choice: "EXACT".to_string(),
        cycle_length_days: 28,
        cycle_length_option: "28 days".to_string(),
        period_duration_days: 5,
        remind_fertile: false,
        typical_symptoms: vec![],
        typical_energy: String::new(),
        typical_sleep: String::new(),
        has_completed_deep_baseline: false,
        is_cloud_sync_allowed: false,
        dynamic_answers: vec![],
    }
}

fn rev(owner: &str, record: &str, revision: i64, server_revision: i64) -> HealthSyncRevision {
    HealthSyncRevision {
        owner_id: owner.to_string(),
        record_id: record.to_string(),
        revision,
        payload_hash: None,
        tombstone: false,
        updated_at: 0,
        server_revision,
    }
}

fn replay(id: &str) -> String {
    let outcome = complete_onboarding(&params(), "PRIVATE", 1_000, false, false);
    let (identity, consent, pop, sop, priors): (Option<&str>, i64, &str, &str, Vec<HealthSyncRevision>) =
        match id {
            "j01" => (Some("u1"), 3, "op-p1", "op-s1", vec![]),
            "j02" => (
                Some("u1"),
                3,
                "op-p2",
                "op-s2",
                vec![rev("u1", "profile", 1, 7), rev("u1", "settings", 4, 2)],
            ),
            "j03" => (Some("  "), 0, "op-p3", "op-s3", vec![]),
            "j04" => (None, 9, "op-p4", "op-s4", vec![]),
            other => panic!("unknown scenario {other}"),
        };
    let writes = plan_completion_journal(
        &outcome,
        &CycleSettings::default(),
        identity,
        consent,
        42,
        pop,
        sop,
        priors.iter().find(|r| r.record_id == "profile"),
        priors.iter().find(|r| r.record_id == "settings"),
    );
    let owner = owner_or_legacy(identity);
    let ob = [&writes.profile_outbox, &writes.settings_outbox]
        .iter()
        .map(|e| {
            format!(
                "{}:{}:{}:{}:{}:{}",
                e.record_id, e.operation_id, e.kind, e.base_revision, e.revision,
                e.consent_generation
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{}|{}|{}|{}|{}|{}|{}",
        id,
        owner,
        writes.profile_revision.revision,
        writes.profile_revision.server_revision,
        writes.settings_revision.revision,
        writes.settings_revision.server_revision,
        ob
    )
}

#[test]
fn completion_journal_matches_the_jvm_row_for_row() {
    let corpus = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/fixtures/journal_corpus.jsonl"
    ))
    .expect("corpus fixture");
    let mut checked = 0;
    for line in corpus.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let id = line.split('|').next().unwrap();
        assert_eq!(&replay(id), line, "scenario {id} drifted from the JVM row");
        checked += 1;
    }
    assert_eq!(checked, 4);
}
