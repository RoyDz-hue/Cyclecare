//! Executed-JVM corpus runner for fetchVerifiedProfile's parse/merge core
//! (VerifiedProfileParity.kt, AOSP org.json classes, 2026-11-02 13:54 EAT).
use cyclecare_core::verified_profile::{ensure_setup_ok, parse_verified_profile, VerifiedProfile};

fn render(r: Result<VerifiedProfile, String>) -> String {
    match r {
        Err(m) => format!("ERROR:{m}"),
        Ok(p) => {
            let mut unlocked = p.unlocked_item_ids.clone();
            unlocked.sort();
            format!(
                "OK|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
                p.user_id,
                p.tier,
                p.rank,
                p.coin_balance,
                p.lifetime_earned,
                p.lifetime_spent,
                p.active_frame.as_deref().unwrap_or("null"),
                p.active_artifact,
                p.active_avatar_art.as_deref().unwrap_or("null"),
                p.active_theme,
                p.last_mined_date.as_deref().unwrap_or("null"),
                unlocked.join(","),
                p.status,
                p.suspension_reason.as_deref().unwrap_or("null"),
                p.suspended_until.as_deref().unwrap_or("null"),
                p.gifted_artifacts.join(",")
            )
        }
    }
}

#[test]
fn verified_profile_matches_executed_jvm_corpus() {
    let corpus = include_str!("../fixtures/oracle/corpus_verified_profile.tsv");
    let results = include_str!("../fixtures/oracle/results_verified_profile.tsv");
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
        assert!(c.len() == 5, "bad corpus row: {line}");
        let got = render(parse_verified_profile(c[1], c[2], c[3], c[4]));
        let want = expected.get(c[0]).unwrap_or_else(|| panic!("missing result for {}", c[0]));
        assert_eq!(&got, want, "case {}", c[0]);
        checked += 1;
    }
    assert_eq!(checked, 21);
}

#[test]
fn ensure_setup_matches_executed_jvm_corpus() {
    let corpus = include_str!("../fixtures/oracle/corpus_ensure_setup.tsv");
    let results = include_str!("../fixtures/oracle/results_ensure_setup.tsv");
    let expected: std::collections::HashMap<&str, &str> = results
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let (id, rest) = l.split_once('\t').unwrap();
            (id, rest)
        })
        .collect();
    for line in corpus.lines().filter(|l| !l.is_empty()) {
        let (id, body) = line.split_once('\t').unwrap();
        let got = ensure_setup_ok(body).to_string();
        assert_eq!(&got, expected.get(id).unwrap(), "case {id}");
    }
}
