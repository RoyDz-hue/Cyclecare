//! consultCare decision gates vs owner-executed JVM (JDK17) verbatim-copy
//! harness /downloads/parity/vm/VmConsultParity.kt (main 31b88f5, :884-947).
//! The harness copies the VM expressions verbatim; isRemoteCareResponse is
//! extracted verbatim from CareAiService.kt :54-58 (constants :35/:38/:52)
//! and is independently corpus-locked in care_ai probes. Orchestration
//! (coroutine, CarePrivacyService, LayeredChatOrchestrator) is not ported.

use cyclecare_core::care_consult::*;
use cyclecare_core::cloud_health_delete as chd;
use cyclecare_core::vm_sync as vs;
use std::path::PathBuf;

fn rows(name: &str) -> Vec<Vec<String>> {
    let p = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle").join(name);
    std::fs::read_to_string(&p)
        .unwrap_or_else(|e| panic!("read {name}: {e}"))
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| l.split('\t').map(String::from).collect())
        .collect()
}

fn dec(t: &str) -> String {
    t.replace("<EMPTY>", "")
        .replace("<SPACES>", "   ")
        .replace("<NBSP>", "\u{a0}")
        .replace("<A1198>", &"a".repeat(1198))
        .replace("<A1199>", &"a".repeat(1199))
        .replace("<A1200>", &"a".repeat(1200))
        .replace("<A1201>", &"a".repeat(1201))
        .replace("<A700>", &"a".repeat(700))
        .replace("<EMOJI>", "\u{1F338}")
}

#[test]
fn care_consult_matches_jvm_outputs() {
    let corpus = rows("corpus_careconsult.tsv");
    let results = rows("results_careconsult.tsv");
    assert_eq!(corpus.len(), results.len(), "corpus/results row count");
    for (c, r) in corpus.iter().zip(results.iter()) {
        let case = &c[0];
        let actual: String = match c[1].as_str() {
            "entry" => match consult_entry(&dec(&c[3]), c[2].parse().unwrap()) {
                Some(q) => format!("ACCEPT:{}", utf16_len(&q)),
                None => "REJECT".to_string(),
            },
            "remote" => remote_allowed(
                c[2].parse().unwrap(),
                c[3].parse().unwrap(),
                c[4].parse().unwrap(),
                c[5].parse().unwrap(),
                c[6].parse().unwrap(),
            )
            .to_string(),
            "ctx" => wants_context_summary(
                c[2].parse().unwrap(),
                c[3].parse().unwrap(),
                c[4].parse().unwrap(),
            )
            .to_string(),
            "current" => is_current_care_request(
                "o1",
                7,
                3,
                if c[2].parse().unwrap() { "o1" } else { "o2" },
                if c[3].parse().unwrap() { 7 } else { 8 },
                if c[4].parse().unwrap() { 3 } else { 4 },
            )
            .to_string(),
            "token" => access_token_for_request(
                c[2].parse().unwrap(),
                c[3].parse().unwrap(),
                if c[4] == "-" { None } else { Some("tok") },
            )
            .unwrap_or("null")
            .to_string(),
            "force" => force_ai(c[2].parse().unwrap(), c[3] != "-").to_string(),
            "allow" => allow_remote_ai(c[2].parse().unwrap(), c[3].parse().unwrap()).to_string(),
            "hist" => {
                let ready: bool = c[2].parse().unwrap();
                let n: usize = c[3].parse().unwrap();
                let turns: Vec<CareHistoryTurn> = (0..n)
                    .map(|i| {
                        let b = 4 + i * 4;
                        CareHistoryTurn {
                            source: &c[b],
                            user_query: &c[b + 1],
                            summary: &c[b + 2],
                            what_text: &c[b + 3],
                        }
                    })
                    .collect();
                // decode fields inside turns
                let decoded: Vec<CareHistoryTurn> = turns
                    .iter()
                    .map(|t| CareHistoryTurn {
                        source: t.source,
                        user_query: t.user_query,
                        summary: t.summary,
                        what_text: t.what_text,
                    })
                    .collect();
                let owned: Vec<[String; 4]> = decoded
                    .iter()
                    .map(|t| {
                        [
                            dec(t.source),
                            dec(t.user_query),
                            dec(t.summary),
                            dec(t.what_text),
                        ]
                    })
                    .collect();
                let turns2: Vec<CareHistoryTurn> = owned
                    .iter()
                    .map(|a| CareHistoryTurn {
                        source: &a[0],
                        user_query: &a[1],
                        summary: &a[2],
                        what_text: &a[3],
                    })
                    .collect();
                history_window(ready, &turns2)
                    .iter()
                    .map(|(role, text)| format!("{role}|{text}"))
                    .collect::<Vec<_>>()
                    .join(";;")
            }
            "items" => {
                if c[2] == "-" {
                    "null".to_string()
                } else {
                    let labels: Vec<String> = c[2].split('|').map(String::from).collect();
                    detected_items_summary(Some(&labels)).unwrap_or_else(|| "null".to_string())
                }
            }
            "fail" => CARE_FAILURE_MESSAGE.to_string(),
            "dentry" => {
                let uid = if c[4] == "-" { None } else { Some(dec(&c[4])) };
                let e = chd::begin(c[2].parse().unwrap(), c[3].parse().unwrap(), uid.as_deref(), "o1", 7);
                match e {
                    chd::BeginOutcome::Busy => "Busy".to_string(),
                    chd::BeginOutcome::NoOnlineAccount => "NoOnlineAccount".to_string(),
                    chd::BeginOutcome::Ready(_) => "Proceed".to_string(),
                }
            }
            "dpause" => {
                let chd::BeginOutcome::Ready(r) = chd::begin(false, false, Some("u"), "o1", 7) else { panic!() };
                match r.after_pause(c[2].parse().unwrap()) {
                    chd::PauseOutcome::Persisted(_) => "proceed".to_string(),
                    chd::PauseOutcome::SaveFailed => chd::MSG_SAVE_FAILED.to_string(),
                }
            }
            "dstate" => {
                let chd::BeginOutcome::Ready(r) = chd::begin(false, false, Some("u"), "o1", 7) else { panic!() };
                let chd::PauseOutcome::Persisted(p) = r.after_pause(true) else { panic!() };
                let st = p.privacy_effects();
                format!("{},{},{},{}", st.is_deleting_cloud_health, st.is_cloud_sync_allowed, st.is_ai_sharing_allowed, st.message)
            }
            "dcurrent" => {
                let chd::BeginOutcome::Ready(r) = chd::begin(false, false, Some("u"), "o1", 7) else { panic!() };
                let chd::PauseOutcome::Persisted(p) = r.after_pause(true) else { panic!() };
                p.permit_rpc(
                    if c[2].parse().unwrap() { "o1" } else { "o2" },
                    if c[3].parse().unwrap() { 7 } else { 8 },
                )
                .is_ok()
                .to_string()
            }
            "dfinal" => {
                let chd::BeginOutcome::Ready(r) = chd::begin(false, false, Some("u"), "o1", 7) else { panic!() };
                let chd::PauseOutcome::Persisted(p) = r.after_pause(true) else { panic!() };
                let permit = p.permit_rpc("o1", 7).expect("permit");
                match permit.complete(c[2].parse().unwrap(), "o1", 7) {
                    chd::Completion::Applied { message } => message.to_string(),
                    chd::Completion::Stale => "stale".to_string(),
                }
            }
            "dpath" => {
                let uid = if c[4] == "-" { None } else { Some(dec(&c[4])) };
                let mut trace = String::new();
                match chd::begin(c[2].parse().unwrap(), c[3].parse().unwrap(), uid.as_deref(), "owner1", 7) {
                    chd::BeginOutcome::Busy => trace.push_str("busy"),
                    chd::BeginOutcome::NoOnlineAccount => {
                        trace.push_str("msg:"); trace.push_str(chd::MSG_NO_ONLINE_ACCOUNT);
                    }
                    chd::BeginOutcome::Ready(r) => match r.after_pause(c[5].parse().unwrap()) {
                        chd::PauseOutcome::SaveFailed => {
                            trace.push_str("msg:"); trace.push_str(chd::MSG_SAVE_FAILED);
                        }
                        chd::PauseOutcome::Persisted(p) => {
                            let st = p.privacy_effects();
                            trace.push_str(&format!("paused;effects({},{},{},{});token;",
                                st.is_deleting_cloud_health, st.is_cloud_sync_allowed,
                                st.is_ai_sharing_allowed, st.message));
                            let stale1: bool = c[6].parse().unwrap();
                            let deleted: bool = c[7].parse().unwrap();
                            let stale2: bool = c[8].parse().unwrap();
                            match p.permit_rpc(if stale1 { "other" } else { "owner1" }, 7) {
                                Err(_) => trace.push_str("stale1"),
                                Ok(permit) => {
                                    trace.push_str("permit;rpc;");
                                    match permit.complete(deleted, if stale2 { "other" } else { "owner1" }, 7) {
                                        chd::Completion::Stale => trace.push_str("stale2"),
                                        chd::Completion::Applied { message } => {
                                            trace.push_str("msg:"); trace.push_str(message);
                                        }
                                    }
                                }
                            }
                        }
                    },
                }
                trace
            }
            "cigate" => {
                let tok = if c[5] == "-" { None } else { Some(dec(&c[5])) };
                let g = cyclecare_core::care_image_gen::care_image_entry_gate(
                    c[2].parse().unwrap(),
                    c[3].parse().unwrap(),
                    c[4].parse().unwrap(),
                    tok.as_deref(),
                    &dec(&c[6]),
                );
                use cyclecare_core::care_image_gen::CareImageGate as G;
                match g {
                    G::Busy => "Busy".to_string(),
                    G::Proceed => "Proceed".to_string(),
                    other => cyclecare_core::care_image_gen::care_image_gate_message(other)
                        .unwrap()
                        .to_string(),
                }
            }
            "reauth" => vs::reauth_after_sync(c[2].parse().unwrap(), c[3].parse().unwrap(), c[4].parse().unwrap()).to_string(),
            "alo" => {
                let (a, b) = vs::autosync_local_only_return(c[2].parse().unwrap());
                format!("{a},{b}")
            }
            "achk" => vs::autosync_checking(c[2].parse().unwrap(), c[3].parse().unwrap()).to_string(),
            "rprobe" => vs::needs_remote_profile_probe(c[2].parse().unwrap(), c[3].parse().unwrap(), c[4].parse().unwrap()).to_string(),
            "telig" => vs::trailing_eligible(c[2].parse().unwrap(), c[3].parse().unwrap()).to_string(),
            "tdisp" => format!("{:?}", vs::trailing_dispatch(c[2].parse().unwrap(), c[3].parse().unwrap(), c[4].parse().unwrap())),
            "rsucc" => vs::reconnect_success(c[2].parse().unwrap(), c[3].parse().unwrap()).to_string(),
            "ccoal" => vs::account_refresh_coalesce(c[2].parse().unwrap()).to_string(),
            "fgate" => vs::on_foreground_autosync(c[2].parse().unwrap()).to_string(),
            "toff" => {
                if cyclecare_core::economy_rules::transfer_may_proceed(c[2].parse().unwrap()) {
                    "proceed".to_string()
                } else {
                    cyclecare_core::economy_rules::TRANSFER_OFFLINE_TOAST.to_string()
                }
            }
            other => panic!("kind {other}"),
        };
        assert_eq!(&actual, &r[1], "care_consult case {case} diverged from JVM");
    }
}
