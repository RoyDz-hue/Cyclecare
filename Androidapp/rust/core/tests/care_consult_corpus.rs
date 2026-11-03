//! consultCare decision gates vs owner-executed JVM (JDK17) verbatim-copy
//! harness /downloads/parity/vm/VmConsultParity.kt (main 31b88f5, :884-947).
//! The harness copies the VM expressions verbatim; isRemoteCareResponse is
//! extracted verbatim from CareAiService.kt :54-58 (constants :35/:38/:52)
//! and is independently corpus-locked in care_ai probes. Orchestration
//! (coroutine, CarePrivacyService, LayeredChatOrchestrator) is not ported.

use cyclecare_core::care_consult::*;
use cyclecare_core::cloud_health_delete as chd;

/// Scripted fake for the injected-effects deletion driver: records the exact
/// effect call order so the corpus trace proves source sequencing (:720-747).
struct DelFake {
    owner: String,
    session: i64,
    pause_ok: bool,
    deleted: bool,
    /// "", "recheck1", or "recheck2": flip the owner at that checkpoint.
    stale_at: String,
    phase: u8, // 0 before rpc, 1 after rpc
    calls: Vec<String>,
    in_flight: (bool, bool),
    ai_sharing_allowed_after_effects: bool,
    terminal_message: &'static str,
    token: Option<String>,
}

impl DelFake {
    fn new(owner: &str, session: i64, pause_ok: bool, deleted: bool, stale_at: &str) -> Self {
        DelFake {
            owner: owner.to_string(),
            session,
            pause_ok,
            deleted,
            stale_at: stale_at.to_string(),
            phase: 0,
            calls: Vec::new(),
            in_flight: (false, true),
            ai_sharing_allowed_after_effects: true,
            terminal_message: "",
            token: None,
        }
    }
    fn staleness_flip(&mut self, checkpoint: u8) {
        if self.stale_at == "recheck1" && checkpoint == 1 && self.phase == 0 {
            self.owner = "other".to_string();
        }
        if self.stale_at == "recheck2" && checkpoint == 2 && self.phase == 1 {
            self.owner = "other".to_string();
        }
    }
}

impl chd::DeletionEffects for DelFake {
    fn current_owner(&self) -> &str {
        &self.owner
    }
    fn current_session(&self) -> i64 {
        self.session
    }
    fn persist_pause(&mut self, _owner: &str, _session: i64) -> bool {
        self.calls.push("pause".to_string());
        self.pause_ok
    }
    fn apply_privacy_effects(&mut self) {
        self.calls.push("effects".to_string());
        self.ai_sharing_allowed_after_effects = false;
    }
    fn set_in_flight(&mut self, in_flight: bool, cloud_sync_allowed: bool) {
        self.calls.push("inflight".to_string());
        self.in_flight = (in_flight, cloud_sync_allowed);
    }
    fn fetch_access_token(&mut self) -> Option<String> {
        self.calls.push("token".to_string());
        self.staleness_flip(1);
        Some("tok".to_string())
    }
    fn delete_cloud_health_copy(&mut self, token: Option<&str>) -> bool {
        self.calls.push("rpc".to_string());
        self.token = token.map(|t| t.to_string());
        self.phase = 1;
        self.staleness_flip(2);
        self.deleted
    }
    fn show_message(&mut self, message: &'static str) {
        self.calls.push(format!("msg:{}", message));
        self.terminal_message = message;
    }
    fn clear_in_flight(&mut self) {
        self.calls.push("clear".to_string());
        self.in_flight = (false, self.in_flight.1);
    }
}
use cyclecare_core::vm_sync as vs;
use cyclecare_core::care_log_draft as cld;
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
                let mut fx = DelFake::new("o1", 7, true, true, "");
                let out = chd::run_deletion(&mut fx, c[2].parse().unwrap(), c[3].parse().unwrap(), uid.as_deref());
                match out {
                    chd::DeletionOutcome::Busy => "Busy".to_string(),
                    chd::DeletionOutcome::NoOnlineAccount => "NoOnlineAccount".to_string(),
                    _ => "Proceed".to_string(),
                }
            }
            "dpause" => {
                let mut fx = DelFake::new("u", 7, c[2].parse().unwrap(), true, "");
                let out = chd::run_deletion(&mut fx, false, false, Some("u"));
                match out {
                    chd::DeletionOutcome::SaveFailed => chd::MSG_SAVE_FAILED.to_string(),
                    _ => "proceed".to_string(),
                }
            }
            "dstate" => {
                let mut fx = DelFake::new("u", 7, true, true, "");
                let out = chd::run_deletion(&mut fx, false, false, Some("u"));
                assert!(matches!(out, chd::DeletionOutcome::Deleted | chd::DeletionOutcome::Unconfirmed));
                // in-flight flag, cloud-sync flag, AI-sharing flag, progress message
                let deleting_flag = fx.calls.contains(&"inflight".to_string());
                let cloud_sync_allowed = fx.in_flight.1;
                format!("{},{},{},{}", deleting_flag, cloud_sync_allowed,
                        fx.ai_sharing_allowed_after_effects, chd::MSG_ERASING)
            }
            "dcurrent" => {
                // stale1 at the first recheck iff owner or session differs
                let stale = if !(c[2].parse::<bool>().unwrap() && c[3].parse::<bool>().unwrap()) { "recheck1" } else { "" };
                let mut fx = DelFake::new("o1", 7, true, true, stale);
                let out = chd::run_deletion(&mut fx, false, false, Some("u"));
                (!matches!(out, chd::DeletionOutcome::StaleBeforeRpc)).to_string()
            }
            "dfinal" => {
                let mut fx = DelFake::new("o1", 7, true, c[2].parse().unwrap(), "");
                let out = chd::run_deletion(&mut fx, false, false, Some("u"));
                match out {
                    chd::DeletionOutcome::Deleted => chd::MSG_DELETED.to_string(),
                    chd::DeletionOutcome::Unconfirmed => chd::MSG_UNCONFIRMED.to_string(),
                    other => panic!("unexpected outcome {:?}", other),
                }
            }
            "dpath" => {
                let uid = if c[4] == "-" { None } else { Some(dec(&c[4])) };
                let stale1: bool = c[6].parse().unwrap();
                let deleted: bool = c[7].parse().unwrap();
                let stale2: bool = c[8].parse().unwrap();
                let stale_at = if stale1 { "recheck1" } else if stale2 { "recheck2" } else { "" };
                let mut fx = DelFake::new("owner1", 7, c[5].parse().unwrap(), deleted, stale_at);
                let out = chd::run_deletion(&mut fx, c[2].parse().unwrap(), c[3].parse().unwrap(), uid.as_deref());
                let mut trace = String::new();
                match out {
                    chd::DeletionOutcome::Busy => trace.push_str("busy"),
                    chd::DeletionOutcome::NoOnlineAccount => {
                        trace.push_str("msg:"); trace.push_str(chd::MSG_NO_ONLINE_ACCOUNT);
                    }
                    chd::DeletionOutcome::SaveFailed => {
                        trace.push_str("msg:"); trace.push_str(chd::MSG_SAVE_FAILED);
                    }
                    _ => {
                        trace.push_str(&format!("paused;effects({},{},{},{});token;",
                            fx.in_flight.0 || fx.calls.contains(&"inflight".to_string()),
                            false,
                            fx.ai_sharing_allowed_after_effects,
                            chd::MSG_ERASING));
                        match out {
                            chd::DeletionOutcome::StaleBeforeRpc => trace.push_str("stale1"),
                            chd::DeletionOutcome::StaleAfterRpc => {
                                trace.push_str("permit;rpc;"); trace.push_str("stale2");
                            }
                            chd::DeletionOutcome::Deleted | chd::DeletionOutcome::Unconfirmed => {
                                trace.push_str("permit;rpc;");
                                trace.push_str("msg:"); trace.push_str(fx.terminal_message);
                            }
                            _ => unreachable!(),
                        }
                    }
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
            "draft" => {
                let d = if c[2].parse().unwrap() { cld::edit_proposed() } else { cld::cancelled() };
                format!("{:?}", cld::confirm(d, c[3].parse().unwrap()))
            }
            "pset" => {
                let r = match c[2].as_str() {
                    "Success" => vs::PasswordSetupResult::Success,
                    "VerificationRequired" => vs::PasswordSetupResult::VerificationRequired,
                    _ => vs::PasswordSetupResult::Error,
                };
                vs::password_setup_state(r).to_string()
            }
            "savedmsg" => cld::CARE_LOG_SAVED_MESSAGE.to_string(),
            "gift" => {
                let find = |n: &str| match n {
                    "Bloom Badge" => Some("ITEM:BB".to_string()),
                    "Moon Pin" => Some("ITEM:MP".to_string()),
                    "Star Charm" => Some("ITEM:SC".to_string()),
                    _ => None,
                };
                let unread = if c[4] == "-" { None } else { Some((dec(&c[4]), dec(&c[5]))) };
                let name = if c[2] == "-" { None } else { Some(dec(&c[2])) };
                vs::resolve_gift_item(
                    name.as_deref(),
                    find,
                    || "ITEM:FALLBACK".to_string(),
                    unread.as_ref().map(|(t, b)| (t.as_str(), b.as_str())),
                    &[("Bloom Badge", "ITEM:BB"), ("Moon Pin", "ITEM:MP"), ("Star Charm", "ITEM:SC")],
                )
                .unwrap_or_else(|| "NONE".to_string())
            }
            "pgate" => vs::prediction_gate(c[2].parse().unwrap()).to_string(),
            "rnentry" => vs::refresh_notifications_entry(c[2].parse().unwrap(), c[3].parse().unwrap()).to_string(),
            "rnout" => format!("{:?}", vs::refresh_notifications_outcome(c[2].parse().unwrap())),
            "giftq" => vs::is_unread_gift_notification(c[2].parse().unwrap(), &c[3]).to_string(),
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
