//! consultCare decision gates vs owner-executed JVM (JDK17) verbatim-copy
//! harness /downloads/parity/vm/VmConsultParity.kt (main 31b88f5, :884-947).
//! The harness copies the VM expressions verbatim; isRemoteCareResponse is
//! extracted verbatim from CareAiService.kt :54-58 (constants :35/:38/:52)
//! and is independently corpus-locked in care_ai probes. Orchestration
//! (coroutine, CarePrivacyService, LayeredChatOrchestrator) is not ported.

use cyclecare_core::care_consult::*;
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
            other => panic!("kind {other}"),
        };
        assert_eq!(&actual, &r[1], "care_consult case {case} diverged from JVM");
    }
}
