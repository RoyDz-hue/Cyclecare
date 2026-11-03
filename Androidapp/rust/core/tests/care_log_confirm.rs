//! confirmCareLog (:856-882) composition + CareConversationPolicy.merge
//! (:58-66) corpus. Merge corpus is JVM-executed (jdk-17.0.21+9, kotlinc
//! 2.1.0) from the verbatim source expression; the confirm body is a
//! scripted-fake transition test over effect call order.

use cyclecare_core::care_log_draft::{
    confirm_care_log_body, merge_log, ConfirmEffects, MergedLogFields, ParsedInterpretation,
};

fn unesc(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next().unwrap() {
            '\\' => out.push('\\'),
            'n' => out.push('\n'),
            't' => out.push('\t'),
            'r' => out.push('\r'),
            'u' => {
                let hex: String = chars.by_ref().take(4).collect();
                out.push(char::from_u32(u32::from_str_radix(&hex, 16).unwrap()).unwrap());
            }
            other => panic!("bad escape {other}"),
        }
    }
    out
}

fn fields(enc: &str) -> (String, String, String, String, i32) {
    let p: Vec<&str> = enc.split('|').collect();
    assert_eq!(p.len(), 5);
    (unesc(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]), p[4].parse().unwrap())
}

#[test]
fn merge_matches_jvm_oracle() {
    let data = std::fs::read_to_string("fixtures/oracle/results_merge.tsv").unwrap();
    let mut n = 0;
    for line in data.lines() {
        let c: Vec<&str> = line.split('\t').collect();
        assert_eq!(c.len(), 4, "row shape: {}", c[0]);
        let (ef, ee, em, es, ep) = fields(c[1]);
        let existing = MergedLogFields { flow_level: ef, energy_level: ee, mood_level: em, symptoms_csv: es, pain_intensity: ep };
        let pp: Vec<&str> = c[2].split('|').collect();
        let opt = |s: &str| if s == "-" { None } else { Some(unesc(s)) };
        let parsed = ParsedInterpretation {
            flow_level_result: opt(pp[0]),
            energy_result: opt(pp[1]),
            mood_result: opt(pp[2]),
            symptoms_result: if pp[3] == "-" { vec![] } else { pp[3].split(';').map(unesc).collect() },
            pain_intensity_result: pp[4].parse().unwrap(),
        };
        let got = merge_log(&existing, &parsed);
        let (wf, we, wm, ws, wp) = fields(c[3]);
        let want = MergedLogFields { flow_level: wf, energy_level: we, mood_level: wm, symptoms_csv: ws, pain_intensity: wp };
        assert_eq!(got, want, "{}: merge mismatch", c[0]);
        n += 1;
    }
    assert_eq!(n, 14);
}

struct ConfirmFx {
    owner: String,
    log: Option<MergedLogFields>,
    flip_owner_at_read: bool,
    calls: Vec<String>,
}

impl ConfirmEffects for ConfirmFx {
    fn effective_user_id(&self) -> &str {
        &self.owner
    }
    fn get_log_for_date(&mut self, _date: &str) -> Option<MergedLogFields> {
        self.calls.push("read".to_string());
        if self.flip_owner_at_read {
            self.owner = "other".to_string();
        }
        self.log.clone()
    }
    fn save_log(&mut self, _m: &MergedLogFields) {
        self.calls.push("save".to_string());
    }
    fn show_saved_message(&mut self) {
        self.calls.push("message".to_string());
    }
    fn update_calculations(&mut self) {
        self.calls.push("calc".to_string());
    }
    fn auto_sync_silently(&mut self) {
        self.calls.push("autosync".to_string());
    }
}

fn interp() -> ParsedInterpretation {
    ParsedInterpretation {
        flow_level_result: Some("HEAVY".to_string()),
        energy_result: None,
        mood_result: None,
        symptoms_result: vec!["cramps".to_string()],
        pain_intensity_result: 6,
    }
}

#[test]
fn confirm_body_source_order() {
    let mut fx = ConfirmFx { owner: "u".into(), log: None, flip_owner_at_read: false, calls: vec![] };
    assert!(confirm_care_log_body(&mut fx, "u", "2026-11-03", &interp()));
    assert_eq!(fx.calls, vec!["read", "save", "message", "calc", "autosync"]);
}

#[test]
fn confirm_body_stale_owner_before_read() {
    let mut fx = ConfirmFx { owner: "other".into(), log: None, flip_owner_at_read: false, calls: vec![] };
    assert!(!confirm_care_log_body(&mut fx, "u", "2026-11-03", &interp()));
    assert!(fx.calls.is_empty());
}

#[test]
fn confirm_body_stale_owner_after_read_blocks_save() {
    // :874 recheck: owner changed during the read -> nothing saved.
    let mut fx = ConfirmFx { owner: "u".into(), log: None, flip_owner_at_read: true, calls: vec![] };
    assert!(!confirm_care_log_body(&mut fx, "u", "2026-11-03", &interp()));
    assert_eq!(fx.calls, vec!["read"]);
}
