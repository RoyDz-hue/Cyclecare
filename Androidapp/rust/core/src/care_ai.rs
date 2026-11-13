//! Port of domain/ai/CareAiService.kt (main@31b88f5): the pure decision layer
//! of the care assistant - source-label classification, request-payload
//! construction, gateway-response validation, and the consult() fallback
//! decision tree. Network I/O, OkHttp, and AuthPreferences reads stay host
//! glue; the tree takes their outcomes as explicit inputs (CloudOutcome).
//!
//! Locked by CareAiParity (parity/ai/careai, results_careai.tsv) executed
//! against the VERBATIM CareAiService.kt + verbatim AiProcessorConsentService/
//! CareConversationPolicy/NaturalLanguageLogParser/SafetyEngine/CycleModels/
//! CareKnowledgeBase compiled with AOSP org.json classes: rows s01-s04,
//! r01-r12, p01-p06, c01-c07.

use crate::care_knowledge::{self, CareArticle};
use crate::kotlin_text::{kotlin_is_blank, kotlin_trim};
use crate::safety::{self, SafetyEvaluation, SafetyLevel, SafetyLog};
use crate::sync_codec::{aosp_opt_string, JsonParser, JsonValue};

pub const REMOTE_CARE_SOURCE: &str = "CycleCare Assistant";
/// Pre-rebrand server label, still accepted so mixed-version fleets stay classified.
pub const LEGACY_REMOTE_CARE_SOURCE: &str = "CycleCare AI";
/// Pre-Assistant rebrand label; old installs and saved history still carry it.
pub const EXPERT_REMOTE_CARE_SOURCE: &str = "CycleCare Expert";

pub const MAX_QUESTION_LENGTH: usize = crate::care_policy::MAX_QUESTION_LENGTH;
pub const MAX_CONTEXT_LENGTH: usize = crate::care_policy::MAX_CONTEXT_LENGTH;

#[derive(Debug, Clone, PartialEq)]
pub struct CareResponse {
    pub summary: String,
    pub what_text: String,
    pub understand_text: String,
    pub care_text: String,
    pub watch_text: String,
    pub escalate_text: String,
    pub safety_alert: Option<SafetyEvaluation>,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CareMemoryMessage {
    pub role: String,
    pub content: String,
}

/// domain/ai/AiProcessorConsentService.kt AiProcessorReceipt.
#[derive(Debug, Clone, PartialEq)]
pub struct AiProcessorReceipt {
    pub provider_id: String,
    pub revision: i64,
    pub manifest_fingerprint: String,
}

/// The server, not Android, chooses the cloud provider; every server-selected
/// source normalizes to the single user-facing label. The argument is read
/// but never used - this is the verbatim production behavior.
pub fn server_selected_source(_selected_value: &str) -> &'static str {
    REMOTE_CARE_SOURCE
}

/// serverSelectedSource(JSONObject): optString("provider","").ifBlank {
/// optString("source","") } - then normalized away by the caller above.
pub fn server_selected_source_json(root: &JsonValue) -> &'static str {
    let provider = aosp_opt_string(root, "provider", "");
    let selected = if kotlin_is_blank(&provider) {
        aosp_opt_string(root, "source", "")
    } else {
        provider
    };
    server_selected_source(&selected)
}

pub fn is_remote_care_response(source: &str) -> bool {
    (source == REMOTE_CARE_SOURCE || source.starts_with(&format!("{REMOTE_CARE_SOURCE} · ")))
        || (source == LEGACY_REMOTE_CARE_SOURCE
            || source.starts_with(&format!("{LEGACY_REMOTE_CARE_SOURCE} · ")))
        || (source == EXPERT_REMOTE_CARE_SOURCE
            || source.starts_with(&format!("{EXPERT_REMOTE_CARE_SOURCE} · ")))
}

/// Kotlin String.length / take(n) count UTF-16 code units, not scalar values.
/// take can split a surrogate pair (Kotlin permits lone surrogates; Rust str
/// cannot hold one) - for astral-plane text at the exact boundary this port
/// keeps the whole scalar instead, a documented, representability-forced
/// divergence. BMP text (all current corpora) is identical.
fn kotlin_len_utf16(s: &str) -> usize {
    s.chars().map(|c| c.len_utf16()).sum()
}

fn kotlin_take(s: &str, n: usize) -> String {
    let mut units = 0usize;
    let mut out = String::new();
    for c in s.chars() {
        units += c.len_utf16();
        if units > n {
            break;
        }
        out.push(c);
    }
    out
}

/// requestPayload(...) - exact key insertion order (AOSP LinkedHashMap).
pub fn request_payload(
    question: &str,
    force_ai: bool,
    context_summary: Option<&str>,
    conversation_history: &[CareMemoryMessage],
    processor_receipts: &[AiProcessorReceipt],
) -> JsonValue {
    let mut pairs: Vec<(String, JsonValue)> = Vec::new();
    pairs.push(("schema_version".to_string(), JsonValue::Int(3)));
    pairs.push((
        "user_question".to_string(),
        JsonValue::Str(kotlin_take(kotlin_trim(question), MAX_QUESTION_LENGTH)),
    ));
    pairs.push(("ai_consent".to_string(), JsonValue::Bool(true)));
    pairs.push((
        "processor_receipts".to_string(),
        JsonValue::Array(
            processor_receipts
                .iter()
                .map(|r| {
                    JsonValue::Object(vec![
                        ("provider_id".to_string(), JsonValue::Str(r.provider_id.clone())),
                        ("revision".to_string(), JsonValue::Int(r.revision as i32)),
                        (
                            "manifest_fingerprint".to_string(),
                            JsonValue::Str(r.manifest_fingerprint.clone()),
                        ),
                    ])
                })
                .collect(),
        ),
    ));
    let history: Vec<&CareMemoryMessage> = conversation_history
        .iter()
        .filter(|m| m.role == "user" || m.role == "assistant")
        .collect();
    if !history.is_empty() {
        let last: Vec<JsonValue> = history
            .iter()
            .rev()
            .take(6)
            .rev()
            .map(|m| {
                JsonValue::Object(vec![
                    ("role".to_string(), JsonValue::Str(m.role.clone())),
                    ("content".to_string(), JsonValue::Str(kotlin_take(&m.content, 600))),
                ])
            })
            .collect();
        pairs.push(("conversation_history".to_string(), JsonValue::Array(last)));
    }
    let expanded = force_ai && context_summary.map(|s| !kotlin_is_blank(s)).unwrap_or(false);
    pairs.push((
        "context_mode".to_string(),
        JsonValue::Str(if expanded { "expanded" } else { "minimal" }.to_string()),
    ));
    if expanded {
        let summary = context_summary.unwrap_or("");
        pairs.push((
            "context".to_string(),
            JsonValue::Object(vec![(
                "summary".to_string(),
                JsonValue::Str(kotlin_take(summary, MAX_CONTEXT_LENGTH)),
            )]),
        ));
    }
    JsonValue::Object(pairs)
}

/// The six validated gateway fields.
#[derive(Debug, Clone, PartialEq)]
pub struct GatewayCareFields {
    pub summary: String,
    pub what_text: String,
    pub understand_text: String,
    pub care_text: String,
    pub watch_text: String,
    pub escalate_text: String,
}

/// AOSP optBoolean(name, false): Boolean as-is; String via
/// Boolean.parseBoolean (equalsIgnoreCase "true"); anything else false.
fn aosp_opt_bool(v: &JsonValue, key: &str) -> bool {
    if let JsonValue::Object(pairs) = v {
        for (k, val) in pairs {
            if k == key {
                return match val {
                    JsonValue::Bool(b) => *b,
                    JsonValue::Str(s) => s.eq_ignore_ascii_case("true"),
                    _ => false,
                };
            }
        }
    }
    false
}

/// Validation block from consult(): raw body already bounded to <= 64 KiB by
/// the host; returns the six fields + normalized source only when
/// root.success && data != null && every field is a non-blank String of
/// UTF-16 length <= 4000.
pub fn parse_gateway_response(raw: &str) -> Option<(GatewayCareFields, &'static str)> {
    let root = JsonParser::new(raw).parse().ok()?;
    if !aosp_opt_bool(&root, "success") {
        return None;
    }
    let data = match &root {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == "data").map(|(_, v)| v),
        _ => None,
    }?;
    let data = match data {
        JsonValue::Object(_) => data,
        _ => return None,
    };
    let fields = ["summary", "whatText", "understandText", "careText", "watchText", "escalateText"];
    let mut values: Vec<String> = Vec::with_capacity(6);
    for f in fields {
        // data.opt(f) is String - raw opt(), NOT optString: AOSP optString
        // would coerce a numeric value via toString and wrongly pass the
        // `is String` production check. getString then returns the String.
        let raw = match data {
            JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == f).map(|(_, v)| v),
            _ => None,
        };
        let v = match raw {
            Some(JsonValue::Str(s)) => s.clone(),
            _ => return None,
        };
        if kotlin_is_blank(&v) || kotlin_len_utf16(&v) > 4000 {
            return None;
        }
        values.push(v);
    }
    Some((
        GatewayCareFields {
            summary: values[0].clone(),
            what_text: values[1].clone(),
            understand_text: values[2].clone(),
            care_text: values[3].clone(),
            watch_text: values[4].clone(),
            escalate_text: values[5].clone(),
        },
        server_selected_source_json(&root),
    ))
}

/// Outcome of the host-executed cloud attempt (consent manifest + receipts
/// gate + HTTP call + body validation). Privacy-snapshot staleness and any
/// exception collapse into these three results exactly as consult() treats
/// them.
#[derive(Debug, Clone, PartialEq)]
pub enum CloudOutcome {
    /// Not attempted: aiSharingAllowed false, blank/absent token, or no prefs.
    NotAttempted,
    /// Manifest missing or no current receipts: the verbatim early-return
    /// "Online explanation unavailable" response.
    ConsentUnavailable,
    /// HTTP/parse/validation failure or privacy snapshot changed: fall through
    /// to the local fallback.
    GatewayFailed,
    /// Validated gateway fields + normalized source.
    GatewaySuccess(GatewayCareFields),
}

pub struct ConsultInput<'a> {
    pub user_question: &'a str,
    pub recent_log: Option<&'a SafetyLog>,
    pub force_ai: bool,
    pub ai_sharing_allowed: bool,
    pub cloud: CloudOutcome,
}

fn alert(eval: &SafetyEvaluation) -> Option<SafetyEvaluation> {
    if eval.level != SafetyLevel::Low {
        Some(eval.clone())
    } else {
        None
    }
}

/// The consult() decision tree with I/O factored out.
pub fn consult_decide(input: &ConsultInput) -> CareResponse {
    let eval = safety::evaluate_input(input.user_question, input.recent_log);
    if eval.is_red_flag {
        return CareResponse {
            summary: eval.title.to_string(),
            what_text: eval.message.clone(),
            understand_text: "This safety guidance is based on what you shared, not a diagnosis."
                .to_string(),
            care_text: eval.action_advice.clone(),
            watch_text:
                "Do not wait for an online explanation if symptoms are severe or worsening."
                    .to_string(),
            escalate_text: eval.action_advice.clone(),
            safety_alert: Some(eval),
            source: "Deterministic Safety Engine".to_string(),
        };
    }
    let article: Option<&'static CareArticle> =
        care_knowledge::search(input.user_question, None).first().copied();
    if article.is_some() && !input.force_ai {
        let a = article.unwrap();
        return CareResponse {
            summary: a.question.to_string(),
            what_text: a.what_text.to_string(),
            understand_text: a.understand_text.to_string(),
            care_text: a.care_text.to_string(),
            watch_text: a.watch_text.to_string(),
            escalate_text: a.escalate_text.to_string(),
            safety_alert: alert(&eval),
            source: "Curated care information".to_string(),
        };
    }
    if input.ai_sharing_allowed {
        match &input.cloud {
            CloudOutcome::ConsentUnavailable => {
                return CareResponse {
                    summary: "Online explanation unavailable".to_string(),
                    what_text:
                        "Online Assistant answers are unavailable until your consent is confirmed."
                            .to_string(),
                    understand_text: article
                        .map(|a| a.understand_text.to_string())
                        .unwrap_or_else(|| "Local guidance remains available.".to_string()),
                    care_text: article
                        .map(|a| a.care_text.to_string())
                        .unwrap_or_else(|| "Try the local care guidance.".to_string()),
                    watch_text: article
                        .map(|a| a.watch_text.to_string())
                        .unwrap_or_else(|| {
                            "Notice symptoms that are new, persistent or worsening.".to_string()
                        }),
                    escalate_text: article
                        .map(|a| a.escalate_text.to_string())
                        .unwrap_or_else(|| {
                            "Seek urgent medical help for severe, sudden or worsening symptoms."
                                .to_string()
                        }),
                    safety_alert: alert(&eval),
                    source: "Local guidance · online explanation unavailable".to_string(),
                };
            }
            CloudOutcome::GatewaySuccess(f) => {
                return CareResponse {
                    summary: f.summary.clone(),
                    what_text: f.what_text.clone(),
                    understand_text: f.understand_text.clone(),
                    care_text: f.care_text.clone(),
                    watch_text: f.watch_text.clone(),
                    escalate_text: f.escalate_text.clone(),
                    safety_alert: alert(&eval),
                    source: REMOTE_CARE_SOURCE.to_string(),
                };
            }
            CloudOutcome::GatewayFailed | CloudOutcome::NotAttempted => {}
        }
    }
    CareResponse {
        summary: if input.force_ai {
            "Deeper explanation unavailable".to_string()
        } else {
            "Let's clarify your question".to_string()
        },
        what_text: if !input.ai_sharing_allowed {
            "Online explanations are off. You can still log, read guidance and use safety support locally.".to_string()
        } else {
            "An online explanation could not be completed. No health entry was saved from this conversation.".to_string()
        },
        understand_text: article
            .map(|a| a.understand_text.to_string())
            .unwrap_or_else(|| {
                "An app cannot diagnose symptoms or confirm ovulation from a calendar estimate."
                    .to_string()
            }),
        care_text: article
            .map(|a| a.care_text.to_string())
            .unwrap_or_else(|| {
                "You can rephrase your question or use the local tracking and care guidance."
                    .to_string()
            }),
        watch_text: article
            .map(|a| a.watch_text.to_string())
            .unwrap_or_else(|| {
                "Notice symptoms that are new, persistent or worsening.".to_string()
            }),
        escalate_text: article
            .map(|a| a.escalate_text.to_string())
            .unwrap_or_else(|| {
                "Seek urgent medical help for severe, sudden or worsening symptoms.".to_string()
            }),
        safety_alert: alert(&eval),
        source: "Local guidance · online explanation unavailable".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::safety::SafetyLog;
    use crate::sync_codec::json_value_to_string;

    fn msg(role: &str, content: &str) -> CareMemoryMessage {
        CareMemoryMessage { role: role.to_string(), content: content.to_string() }
    }

    #[test]
    fn remote_source_classification_locked() {
        assert_eq!(is_remote_care_response("CycleCare Assistant"), true); // r01
        assert_eq!(is_remote_care_response("CycleCare Assistant · cohere"), true); // r02
        assert_eq!(is_remote_care_response("CycleCare AI"), true); // r03
        assert_eq!(is_remote_care_response("CycleCare AI · x"), true); // r04
        assert_eq!(is_remote_care_response("CycleCare Expert"), true); // r05
        assert_eq!(is_remote_care_response("CycleCare Expert · y"), true); // r06
        assert_eq!(is_remote_care_response("CycleCare AssistantX"), false); // r07
        assert_eq!(is_remote_care_response("Curated care information"), false); // r08
        assert_eq!(is_remote_care_response("cyclecare assistant"), false); // r09
        assert_eq!(is_remote_care_response(" CycleCare Assistant"), false); // r10
        assert_eq!(is_remote_care_response("CycleCare Assistant "), false); // r11
        assert_eq!(is_remote_care_response(""), false); // r12
        assert_eq!(server_selected_source("cohere"), "CycleCare Assistant"); // s01
        assert_eq!(server_selected_source(""), "CycleCare Assistant"); // s02
    }

    #[test]
    fn request_payload_locked() {
        assert_eq!(json_value_to_string(&request_payload("What does discharge mean?  ", false, None, &[], &[])), "{\"schema_version\":3,\"user_question\":\"What does discharge mean?\",\"ai_consent\":true,\"processor_receipts\":[],\"context_mode\":\"minimal\"}"); // p01
        assert_eq!(json_value_to_string(&request_payload("q", true, Some("cycle summary here"), &[], &[])), "{\"schema_version\":3,\"user_question\":\"q\",\"ai_consent\":true,\"processor_receipts\":[],\"context_mode\":\"expanded\",\"context\":{\"summary\":\"cycle summary here\"}}"); // p02
        assert_eq!(json_value_to_string(&request_payload("q", true, Some("   "), &[], &[])), "{\"schema_version\":3,\"user_question\":\"q\",\"ai_consent\":true,\"processor_receipts\":[],\"context_mode\":\"minimal\"}"); // p03
        assert_eq!(json_value_to_string(&request_payload("q", false, Some("cycle summary here"), &[], &[])), "{\"schema_version\":3,\"user_question\":\"q\",\"ai_consent\":true,\"processor_receipts\":[],\"context_mode\":\"minimal\"}"); // p04
        let hist = vec![
            msg("system", "sys"), msg("user", "u1"), msg("assistant", "a1"),
            msg("user", "u2"), msg("assistant", "a2"), msg("user", "u3"),
            msg("assistant", "a3"), msg("user", "u4"), msg("assistant", &"x".repeat(700)),
        ];
        assert_eq!(json_value_to_string(&request_payload("q", false, None, &hist, &[])), "{\"schema_version\":3,\"user_question\":\"q\",\"ai_consent\":true,\"processor_receipts\":[],\"conversation_history\":[{\"role\":\"user\",\"content\":\"u2\"},{\"role\":\"assistant\",\"content\":\"a2\"},{\"role\":\"user\",\"content\":\"u3\"},{\"role\":\"assistant\",\"content\":\"a3\"},{\"role\":\"user\",\"content\":\"u4\"},{\"role\":\"assistant\",\"content\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}],\"context_mode\":\"minimal\"}"); // p05
        let receipts = vec![
            AiProcessorReceipt { provider_id: "COHERE".into(), revision: 2, manifest_fingerprint: "f".repeat(64) },
            AiProcessorReceipt { provider_id: "GEMINI".into(), revision: 1, manifest_fingerprint: "a".repeat(64) },
        ];
        assert_eq!(json_value_to_string(&request_payload(&format!("  {}", "w".repeat(1300)), true, Some("ctx"), &[], &receipts)), "{\"schema_version\":3,\"user_question\":\"wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww\",\"ai_consent\":true,\"processor_receipts\":[{\"provider_id\":\"COHERE\",\"revision\":2,\"manifest_fingerprint\":\"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff\"},{\"provider_id\":\"GEMINI\",\"revision\":1,\"manifest_fingerprint\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}],\"context_mode\":\"expanded\",\"context\":{\"summary\":\"ctx\"}}"); // p06
    }

    #[test]
    fn consult_decision_tree_locked() {
        fn fields(s: &str) -> std::collections::HashMap<String, String> {
            match crate::sync_codec::JsonParser::new(s).parse().unwrap() {
                JsonValue::Object(pairs) => pairs.into_iter().map(|(k, v)| match v {
                    JsonValue::Str(x) => (k, x), _ => (k, String::new()) }).collect(),
                _ => panic!("expected object"),
            }
        }
        fn check(r: &CareResponse, exp: &str) {
            let v = fields(exp);
            let g = |k: &str| v.get(k).cloned().unwrap_or_default();
            assert_eq!(r.summary, g("summary"));
            assert_eq!(r.what_text, g("whatText"));
            assert_eq!(r.understand_text, g("understandText"));
            assert_eq!(r.care_text, g("careText"));
            assert_eq!(r.watch_text, g("watchText"));
            assert_eq!(r.escalate_text, g("escalateText"));
            assert_eq!(r.source, g("source"));
            let lvl = g("safetyLevel");
            if lvl.is_empty() {
                assert!(r.safety_alert.is_none());
            } else {
                assert_eq!(r.safety_alert.as_ref().unwrap().level.name(), lvl);
            }
        }
        check(&consult_decide(&ConsultInput { user_question: "I fainted and my bleeding is unusually heavy", recent_log: None, force_ai: false, ai_sharing_allowed: false, cloud: CloudOutcome::NotAttempted }), "{\"summary\":\"Urgent medical attention advised\",\"whatText\":\"Fainting and unusually heavy bleeding were mentioned together.\",\"understandText\":\"This safety guidance is based on what you shared, not a diagnosis.\",\"careText\":\"Please seek urgent medical care now, especially if symptoms are continuing or worsening.\",\"watchText\":\"Do not wait for an online explanation if symptoms are severe or worsening.\",\"escalateText\":\"Please seek urgent medical care now, especially if symptoms are continuing or worsening.\",\"safetyLevel\":\"URGENT\",\"source\":\"Deterministic Safety Engine\"}"); // c01
        check(&consult_decide(&ConsultInput { user_question: "Why am I so tired before my period?", recent_log: None, force_ai: false, ai_sharing_allowed: false, cloud: CloudOutcome::NotAttempted }), "{\"summary\":\"Why am I so tired before my period?\",\"whatText\":\"A noticeable decline in physical stamina and mental focus in the 3 to 7 days before menstruation.\",\"understandText\":\"After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.\",\"careText\":\"Consider magnesium-containing foods if they suit you, prioritize rest, and choose gentler movement if intense exercise feels difficult.\",\"watchText\":\"Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.\",\"escalateText\":\"Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance).\",\"safetyLevel\":\"REVIEW\",\"source\":\"Curated care information\"}"); // c02
        check(&consult_decide(&ConsultInput { user_question: "zzq qqzz", recent_log: None, force_ai: false, ai_sharing_allowed: false, cloud: CloudOutcome::NotAttempted }), "{\"summary\":\"Let's clarify your question\",\"whatText\":\"Online explanations are off. You can still log, read guidance and use safety support locally.\",\"understandText\":\"An app cannot diagnose symptoms or confirm ovulation from a calendar estimate.\",\"careText\":\"You can rephrase your question or use the local tracking and care guidance.\",\"watchText\":\"Notice symptoms that are new, persistent or worsening.\",\"escalateText\":\"Seek urgent medical help for severe, sudden or worsening symptoms.\",\"safetyLevel\":\"REVIEW\",\"source\":\"Local guidance · online explanation unavailable\"}"); // c03
        check(&consult_decide(&ConsultInput { user_question: "zzq qqzz", recent_log: None, force_ai: true, ai_sharing_allowed: false, cloud: CloudOutcome::NotAttempted }), "{\"summary\":\"Deeper explanation unavailable\",\"whatText\":\"Online explanations are off. You can still log, read guidance and use safety support locally.\",\"understandText\":\"An app cannot diagnose symptoms or confirm ovulation from a calendar estimate.\",\"careText\":\"You can rephrase your question or use the local tracking and care guidance.\",\"watchText\":\"Notice symptoms that are new, persistent or worsening.\",\"escalateText\":\"Seek urgent medical help for severe, sudden or worsening symptoms.\",\"safetyLevel\":\"REVIEW\",\"source\":\"Local guidance · online explanation unavailable\"}"); // c04
        check(&consult_decide(&ConsultInput { user_question: "zzq qqzz", recent_log: None, force_ai: false, ai_sharing_allowed: true, cloud: CloudOutcome::ConsentUnavailable }), "{\"summary\":\"Online explanation unavailable\",\"whatText\":\"Online Assistant answers are unavailable until your consent is confirmed.\",\"understandText\":\"Local guidance remains available.\",\"careText\":\"Try the local care guidance.\",\"watchText\":\"Notice symptoms that are new, persistent or worsening.\",\"escalateText\":\"Seek urgent medical help for severe, sudden or worsening symptoms.\",\"safetyLevel\":\"REVIEW\",\"source\":\"Local guidance · online explanation unavailable\"}"); // c05
        check(&consult_decide(&ConsultInput { user_question: "Why am I so tired before my period?", recent_log: None, force_ai: true, ai_sharing_allowed: true, cloud: CloudOutcome::ConsentUnavailable }), "{\"summary\":\"Online explanation unavailable\",\"whatText\":\"Online Assistant answers are unavailable until your consent is confirmed.\",\"understandText\":\"After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.\",\"careText\":\"Consider magnesium-containing foods if they suit you, prioritize rest, and choose gentler movement if intense exercise feels difficult.\",\"watchText\":\"Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.\",\"escalateText\":\"Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance).\",\"safetyLevel\":\"REVIEW\",\"source\":\"Local guidance · online explanation unavailable\"}"); // c06
        let faint_log = SafetyLog { symptoms_csv: "fainting".into(), flow_level: "UNUSUALLY_HEAVY".into(), ..Default::default() };
        check(&consult_decide(&ConsultInput { user_question: "just checking in", recent_log: Some(&faint_log), force_ai: false, ai_sharing_allowed: false, cloud: CloudOutcome::NotAttempted }), "{\"summary\":\"Urgent medical attention advised\",\"whatText\":\"Fainting and unusually heavy bleeding were mentioned together.\",\"understandText\":\"This safety guidance is based on what you shared, not a diagnosis.\",\"careText\":\"Please seek urgent medical care now, especially if symptoms are continuing or worsening.\",\"watchText\":\"Do not wait for an online explanation if symptoms are severe or worsening.\",\"escalateText\":\"Please seek urgent medical care now, especially if symptoms are continuing or worsening.\",\"safetyLevel\":\"URGENT\",\"source\":\"Deterministic Safety Engine\"}"); // c07
    }

    #[test]
    fn gateway_response_validation() {
        let ok = r#"{"success":true,"provider":"cohere","data":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#;
        let (f, src) = parse_gateway_response(ok).unwrap();
        assert_eq!(f.summary, "s");
        assert_eq!(src, "CycleCare Assistant");
        // success string form accepted (AOSP parseBoolean)
        let ok2 = r#"{"success":"true","data":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#;
        assert!(parse_gateway_response(ok2).is_some());
        // success TRUE uppercase
        let ok3 = ok2.replace("\"true\"", "\"TRUE\"");
        assert!(parse_gateway_response(&ok3).is_some());
        // missing success
        assert!(parse_gateway_response(r#"{"data":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#).is_none());
        // success false
        assert!(parse_gateway_response(r#"{"success":false,"data":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#).is_none());
        // data not an object
        assert!(parse_gateway_response(r#"{"success":true,"data":"x"}"#).is_none());
        // blank field rejected
        assert!(parse_gateway_response(r#"{"success":true,"data":{"summary":" ","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#).is_none());
        // numeric field rejected (opt is String check)
        assert!(parse_gateway_response(r#"{"success":true,"data":{"summary":5,"whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"#).is_none());
        // >4000 UTF-16 units rejected
        let long = "y".repeat(4001);
        let body = format!(r#"{{"success":true,"data":{{"summary":"{}","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}}}"#, long);
        assert!(parse_gateway_response(&body).is_none());
        let exactly = "y".repeat(4000);
        let body = format!(r#"{{"success":true,"data":{{"summary":"{}","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}}}"#, exactly);
        assert!(parse_gateway_response(&body).is_some());
        // malformed JSON
        assert!(parse_gateway_response("{not json").is_none());
    }
}