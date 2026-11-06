//! Comparator: sync_protocol.rs vs executed Kotlin HealthSyncProtocol
//! (verbatim extract, kotlinc 2.1.0/JRE 21): fixtures/oracle/results_syncproto.tsv.

use cyclecare_core::sync_protocol::*;
use std::path::PathBuf;

fn render(r: &ApplyResult) -> String {
    match r {
        ApplyResult::Applied(rec) => format!(
            "Applied({},{},{})", rec.revision,
            rec.payload_hash.as_deref().unwrap_or("null"), rec.tombstone
        ),
        ApplyResult::Duplicate(rec) => format!(
            "Duplicate({},{},{})", rec.revision,
            rec.payload_hash.as_deref().unwrap_or("null"), rec.tombstone
        ),
        ApplyResult::Rejected(reason) => format!("Rejected({})", reason),
        ApplyResult::Conflict(c) => format!(
            "Conflict({},local.base={},local.rev={},remote.rev={},remote.hash={},remote.tomb={})",
            c.record_id, c.local.base_revision, c.local.revision, c.remote.revision,
            c.remote.payload_hash.as_deref().unwrap_or("null"), c.remote.tombstone
        ),
    }
}

fn op(id: &str, rec: &str, kind: SyncOperationKind, base: i64, rev: i64, hash: &str, gen: i64, owner: &str) -> SyncOperation {
    SyncOperation {
        operation_id: id.to_string(),
        owner_id: owner.to_string(),
        record_id: rec.to_string(),
        kind,
        base_revision: base,
        revision: rev,
        payload_hash: hash.to_string(),
        consent_generation: gen,
    }
}

#[test]
fn state_machine_matches_kotlin_outputs() {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle");
    let results = std::fs::read_to_string(dir.join("results_syncproto.tsv")).unwrap();
    let corpus = std::fs::read_to_string(dir.join("corpus_syncproto.tsv")).unwrap();
    let mut checked = 0;
    for (line, expected) in corpus.lines().filter(|l| !l.is_empty()).zip(results.lines()) {
        let cols: Vec<&str> = line.split('\t').collect();
        let exp_cols: Vec<&str> = expected.split('\t').collect();
        assert_eq!(cols[0], exp_cols[0]);
        let mut sm = HealthSyncStateMachine::new("owner1");
        let mut out: Vec<String> = Vec::new();
        for step in &cols[1..] {
            let p: Vec<&str> = step.split(':').collect();
            out.push(match p[0] {
                "grant" => format!("gen={}", sm.grant_consent()),
                "revoke" => {
                    sm.revoke_consent();
                    "revoked".to_string()
                }
                "gen" => format!("gen={}", sm.current_consent_generation()),
                "apply" => render(&sm.apply(op(
                    p[1], p[2],
                    if p[3] == "UPSERT" { SyncOperationKind::Upsert } else { SyncOperationKind::Delete },
                    p[4].parse().unwrap(), p[5].parse().unwrap(), p[6], p[7].parse().unwrap(), "owner1",
                ))),
                "applyOwner" => render(&sm.apply(op(
                    p[1], p[2],
                    if p[3] == "UPSERT" { SyncOperationKind::Upsert } else { SyncOperationKind::Delete },
                    p[4].parse().unwrap(), p[5].parse().unwrap(), p[6], p[7].parse().unwrap(), p[8],
                ))),
                _ => "?".to_string(),
            });
        }
        assert_eq!(out.join("\t"), exp_cols[1..].join("\t"), "scenario {} mismatch", cols[0]);
        checked += 1;
    }
    assert_eq!(checked, 12);
}
