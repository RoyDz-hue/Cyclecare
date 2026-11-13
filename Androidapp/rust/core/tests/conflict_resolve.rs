//! Conflict-resolution guard tests.

use cyclecare_core::conflict_resolve::*;

fn snap(owner: &str, gen: u64) -> ConflictSnapshot {
    ConflictSnapshot { owner: owner.to_string(), session_generation: gen }
}

#[test]
fn re_entry_is_refused_while_resolving() {
    let mut r = ConflictResolver::new();
    let s = snap("roy", 1);
    assert_eq!(r.begin(&s, &s), BeginVerdict::Started);
    assert_eq!(r.begin(&s, &s), BeginVerdict::AlreadyResolving);
}

#[test]
fn stale_snapshot_at_begin_refuses_to_start() {
    let mut r = ConflictResolver::new();
    assert_eq!(r.begin(&snap("roy", 1), &snap("roy", 2)), BeginVerdict::StaleSnapshot);
}

#[test]
fn identity_change_mid_resolution_aborts_without_publishing() {
    let mut r = ConflictResolver::new();
    let s = snap("roy", 1);
    r.begin(&s, &s);
    // Owner switches before the write lands.
    let verdict = r.finish(&s, &snap("someone-else", 2), false, false);
    assert_eq!(verdict, None, "no result may be published for a stale identity");
}

#[test]
fn honest_outcome_messages() {
    let mut r = ConflictResolver::new();
    let s = snap("roy", 1);
    r.begin(&s, &s);
    assert_eq!(r.finish(&s, &s, true, false), Some(ResolveMessage::SaveFailed));
    r.begin(&s, &s);
    assert_eq!(r.finish(&s, &s, false, true), Some(ResolveMessage::ChangedDuringReview));
    r.begin(&s, &s);
    assert_eq!(r.finish(&s, &s, false, false), Some(ResolveMessage::SavedNotMerged),
        "success copy must not claim an automatic merge");
}

#[test]
fn identity_change_resets_guard() {
    let mut r = ConflictResolver::new();
    let s = snap("roy", 1);
    r.begin(&s, &s);
    r.on_identity_change();
    assert_eq!(r.begin(&s, &s), BeginVerdict::Started);
}
