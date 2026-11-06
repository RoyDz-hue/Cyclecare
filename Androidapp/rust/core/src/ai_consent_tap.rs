//! Port of decideAiConsentTap + binding equality (main@31b88f5,
//! domain/ai/AiConsentTap.kt) over the verbatim manifest model shapes
//! (domain/ai/AiProcessorConsentService.kt:17-22). Grants bind to the exact
//! displayed snapshot; any field drift - including same-revision provider or
//! fingerprint changes - fails closed. Executed JVM locks:
//! /downloads/parity/ai/ConsentTapParity.kt ac01-ac09 (2026-11-02 14:04 EAT).

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiSubProcessor {
    pub id: String,
    pub name: String,
    pub policy_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiProcessor {
    pub id: String,
    pub display_name: String,
    pub model: String,
    pub policy_revision: String,
    pub manifest_fingerprint: String,
    pub processors: Vec<AiSubProcessor>,
    pub eligible: bool,
    pub unavailable_reason: String,
    pub consent_enabled: bool,
    pub consent_revision: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiProcessorManifest {
    pub version: i32,
    pub account_snapshot: String,
    pub manifest_revision: String,
    pub providers: Vec<AiProcessor>,
    pub hybrid_released: bool,
    pub hybrid_max_attempts: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AiConsentTapDecision {
    Enable(Vec<String>),
    FailClosed(String),
}

/// The displayed-snapshot projection: (manifestRevision, version,
/// accountSnapshot) plus each provider's binding fields.
fn binding(m: &AiProcessorManifest) -> ((&str, i32, &str), Vec<(&str, &str, bool, bool, i32, &str, Vec<String>)>) {
    (
        (m.manifest_revision.as_str(), m.version, m.account_snapshot.as_str()),
        m.providers
            .iter()
            .map(|p| {
                (
                    p.id.as_str(),
                    p.manifest_fingerprint.as_str(),
                    p.eligible,
                    p.consent_enabled,
                    p.consent_revision,
                    p.policy_revision.as_str(),
                    p.processors
                        .iter()
                        .map(|s| format!("{}|{}", s.id, s.policy_url))
                        .collect::<Vec<_>>(),
                )
            })
            .collect(),
    )
}

/// True only when two manifest snapshots bind identically, field-for-field.
pub fn ai_consent_binding_equal(a: Option<&AiProcessorManifest>, b: Option<&AiProcessorManifest>) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => binding(a) == binding(b),
        _ => false,
    }
}

pub fn decide_ai_consent_tap(
    displayed: Option<&AiProcessorManifest>,
    current: Option<&AiProcessorManifest>,
) -> AiConsentTapDecision {
    let Some(displayed) = displayed else {
        return AiConsentTapDecision::FailClosed(
            "Review who can answer before allowing. Nothing was enabled.".into(),
        );
    };
    let Some(live) = current else {
        return AiConsentTapDecision::FailClosed(
            "The provider list is unavailable. Nothing was enabled.".into(),
        );
    };
    if !ai_consent_binding_equal(Some(displayed), Some(live)) {
        return AiConsentTapDecision::FailClosed(
            "The provider list changed. Review it again before allowing.".into(),
        );
    }
    let ids: Vec<String> = displayed
        .providers
        .iter()
        .filter(|p| p.eligible && p.consent_enabled)
        .map(|p| p.id.clone())
        .collect();
    if ids.is_empty() {
        return AiConsentTapDecision::FailClosed(
            "No answer provider is available right now. Nothing was enabled.".into(),
        );
    }
    AiConsentTapDecision::Enable(ids)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sub(url: &str) -> AiSubProcessor {
        AiSubProcessor { id: "s1".into(), name: "S".into(), policy_url: url.into() }
    }

    fn prov(id: &str, fp: &str, eligible: bool, enabled: bool, subs: Vec<AiSubProcessor>) -> AiProcessor {
        AiProcessor {
            id: id.into(), display_name: "D".into(), model: "m".into(), policy_revision: "p1".into(),
            manifest_fingerprint: fp.into(), processors: subs, eligible,
            unavailable_reason: String::new(), consent_enabled: enabled, consent_revision: 1,
        }
    }

    fn mani(ps: Vec<AiProcessor>, version: i32, account: &str) -> AiProcessorManifest {
        AiProcessorManifest {
            version, account_snapshot: account.into(), manifest_revision: "r1".into(),
            providers: ps, hybrid_released: false, hybrid_max_attempts: 3,
        }
    }

    fn base() -> AiProcessorManifest {
        mani(vec![prov("p1", "fp", true, true, vec![sub("https://x")]), prov("p2", "fp", true, true, vec![sub("https://x")])], 1, "acc")
    }

    fn render(d: AiConsentTapDecision) -> String {
        match d {
            AiConsentTapDecision::Enable(ids) => format!("ENABLE:{}", ids.join(",")),
            AiConsentTapDecision::FailClosed(r) => format!("FAIL:{r}"),
        }
    }

    // Executed JVM locks (ConsentTapParity.kt, 2026-11-02 14:04 EAT).
    #[test]
    fn consent_tap_matches_executed_jvm_probes() {
        let a = base();
        assert_eq!(render(decide_ai_consent_tap(None, Some(&a))), "FAIL:Review who can answer before allowing. Nothing was enabled.");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), None)), "FAIL:The provider list is unavailable. Nothing was enabled.");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), Some(&base()))), "ENABLE:p1,p2");
        let drift_fp = mani(vec![prov("p1", "fp2", true, true, vec![sub("https://x")]), prov("p2", "fp", true, true, vec![sub("https://x")])], 1, "acc");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), Some(&drift_fp))), "FAIL:The provider list changed. Review it again before allowing.");
        let none_eligible = mani(vec![prov("p1", "fp", false, true, vec![sub("https://x")])], 1, "acc");
        assert_eq!(render(decide_ai_consent_tap(Some(&none_eligible), Some(&none_eligible))), "FAIL:No answer provider is available right now. Nothing was enabled.");
        let v2 = mani(vec![prov("p1", "fp", true, true, vec![sub("https://x")]), prov("p2", "fp", true, true, vec![sub("https://x")])], 2, "acc");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), Some(&v2))), "FAIL:The provider list changed. Review it again before allowing.");
        let disabled = mani(vec![prov("p1", "fp", true, true, vec![sub("https://x")]), prov("p2", "fp", true, false, vec![sub("https://x")])], 1, "acc");
        assert_eq!(render(decide_ai_consent_tap(Some(&disabled), Some(&disabled))), "ENABLE:p1");
        let drift_sub = mani(vec![prov("p1", "fp", true, true, vec![sub("https://y")]), prov("p2", "fp", true, true, vec![sub("https://x")])], 1, "acc");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), Some(&drift_sub))), "FAIL:The provider list changed. Review it again before allowing.");
        let acc2 = mani(vec![prov("p1", "fp", true, true, vec![sub("https://x")]), prov("p2", "fp", true, true, vec![sub("https://x")])], 1, "acc2");
        assert_eq!(render(decide_ai_consent_tap(Some(&a), Some(&acc2))), "FAIL:The provider list changed. Review it again before allowing.");
    }
}
