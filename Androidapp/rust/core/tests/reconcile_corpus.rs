//! Executed-JVM corpus runner for reconcile_offline_claim_response
//! (ReconcileParity.kt, AOSP org.json classes, 2026-11-02 13:57 EAT).
use bigdecimal::BigDecimal;
use cyclecare_core::reconcile::{reconcile_offline_claim_response, ReconcileOutcome};
use std::str::FromStr;

#[test]
fn reconcile_matches_executed_jvm_corpus() {
    let corpus = include_str!("../fixtures/oracle/corpus_reconcile.tsv");
    let results = include_str!("../fixtures/oracle/results_reconcile.tsv");
    let expected: std::collections::HashMap<&str, &str> = results
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let (id, rest) = l.split_once('\t').unwrap();
            (id, rest)
        })
        .collect();
    let mut checked = 0;
    for line in corpus.lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        assert_eq!(c.len(), 7, "bad row {line}");
        let body = if c[2] == "<EMPTY>" { "" } else { c[2] };
        let outcome = reconcile_offline_claim_response(
            c[1] == "true",
            body,
            &BigDecimal::from_str(c[3]).unwrap(),
            &BigDecimal::from_str(c[4]).unwrap(),
            c[5],
            c[6],
        );
        let got = match outcome {
            ReconcileOutcome::Applied { coin_balance, lifetime_earned, rank, mined_date } => {
                format!("APPLIED|{coin_balance}|{lifetime_earned}|{rank}|{mined_date}")
            }
            ReconcileOutcome::AlreadyRecorded => "ALREADY_RECORDED".to_string(),
            ReconcileOutcome::Refetch => "REFETCH".to_string(),
            ReconcileOutcome::OfflineKept => "OFFLINE_KEPT".to_string(),
        };
        assert_eq!(&got, expected.get(c[0]).unwrap(), "case {}", c[0]);
        checked += 1;
    }
    assert_eq!(checked, 12);
}

#[test]
fn has_remote_profile_matches_executed_jvm_corpus() {
    let corpus = include_str!("../fixtures/oracle/corpus_remote_profile.tsv");
    let results = include_str!("../fixtures/oracle/results_remote_profile.tsv");
    let expected: std::collections::HashMap<&str, &str> = results
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let (id, rest) = l.split_once('\t').unwrap();
            (id, rest)
        })
        .collect();
    for line in corpus.lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        let got = match cyclecare_core::reconcile::has_remote_profile_response(c[1] == "true", c[2], c[3]) {
            Some(true) => "true",
            Some(false) => "false",
            None => "null",
        };
        assert_eq!(got, *expected.get(c[0]).unwrap(), "case {}", c[0]);
    }
}
