//! Port of domain/ai/LayeredChatOrchestrator.kt (main@31b88f5): the raw-chat
//! decision tree - red-flag override, deterministic intent matching (economy/
//! payment keyword chains), personal-note gating, KB article fallback, the
//! CASE B clarification card, and the remote-enrichment swap rules.
//!
//! Pure port: the remote consult is supplied by the host as RemoteAttempt
//! (consult() itself is care_ai::consult_decide + transport glue). The
//! formatted coin balance is supplied by the host via the coin_format port
//! (CoinFormatter.format(BigDecimal) - display-only). All copy is verbatim,
//! including unsupported-feature safety text ("Do not send a payment.").
//!
//! Locked by LayeredParity (parity/ai/careai, results_layered.tsv, 13 rows)
//! executed against the VERBATIM LayeredChatOrchestrator.kt + verbatim
//! CareAiService/KnowledgeBase/ConversationPolicy/NLLogParser/SafetyEngine/
//! EconomyModels/CoinFormatter with AOSP org.json.

use crate::care_ai::{is_remote_care_response, CareResponse};
use crate::care_knowledge;
use crate::care_policy;
use crate::kotlin_text::kotlin_trim;
use crate::log_parser;
use crate::safety::{self, SafetyEvaluation, SafetyLevel, SafetyLog};

#[derive(Debug, Clone, PartialEq)]
pub struct TiedDownChoice {
    pub id: &'static str,
    pub icon_emoji: &'static str,
    pub label: &'static str,
    pub prefilled_query: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EconomyChatIntent {
    BalanceInquiry,
    ArtifactCatalog,
    CoinStore,
    TransferInquiry,
    MiningProgress,
}

impl EconomyChatIntent {
    pub fn name(&self) -> &'static str {
        match self {
            EconomyChatIntent::BalanceInquiry => "BALANCE_INQUIRY",
            EconomyChatIntent::ArtifactCatalog => "ARTIFACT_CATALOG",
            EconomyChatIntent::CoinStore => "COIN_STORE",
            EconomyChatIntent::TransferInquiry => "TRANSFER_INQUIRY",
            EconomyChatIntent::MiningProgress => "MINING_PROGRESS",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaymentChatIntent {
    MpesaTopup,
    CheckPending,
    PaymentHelp,
}

impl PaymentChatIntent {
    pub fn name(&self) -> &'static str {
        match self {
            PaymentChatIntent::MpesaTopup => "MPESA_TOPUP",
            PaymentChatIntent::CheckPending => "CHECK_PENDING",
            PaymentChatIntent::PaymentHelp => "PAYMENT_HELP",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct LayeredChatResult {
    pub main_response: CareResponse,
    pub detected_symptoms: Vec<String>,
    pub detected_flow: Option<String>,
    pub detected_energy: Option<String>,
    pub detected_mood: Option<String>,
    pub pain_intensity: i32,
    pub gentle_progress_coins_awarded: i64,
    pub economy_intent: Option<EconomyChatIntent>,
    pub payment_intent: Option<PaymentChatIntent>,
    pub is_clarification_needed: bool,
    pub clarification_message: Option<String>,
    pub tied_down_choices: Vec<TiedDownChoice>,
    pub raw_query: String,
}

/// What the host did about the remote-enrichment branch.
#[derive(Debug, Clone, PartialEq)]
pub enum RemoteAttempt {
    /// Not allowed (allowRemoteAi false, or token null/blank): consult is
    /// never called.
    NotAllowed,
    /// consult() ran; carries its full CareResponse (any source, local or
    /// remote).
    Guided(CareResponse),
}

const MPESA_UNAVAILABLE: &str =
    "M-Pesa checkout is currently unavailable while payment safety review is incomplete. Do not send a payment. ";

fn alert(eval: &SafetyEvaluation) -> Option<SafetyEvaluation> {
    if eval.level != SafetyLevel::Low {
        Some(eval.clone())
    } else {
        None
    }
}

/// processRawChat with the consult call factored out.
pub fn process_raw_chat(
    raw_input: &str,
    today_log: Option<&SafetyLog>,
    formatted_coin_balance: &str,
    force_ai: bool,
    remote: RemoteAttempt,
) -> LayeredChatResult {
    let trimmed = kotlin_trim(raw_input);
    let safety_eval = safety::evaluate_input(trimmed, today_log);
    if safety_eval.is_red_flag {
        return LayeredChatResult {
            main_response: CareResponse {
                summary: "Please seek medical help".to_string(),
                what_text: safety_eval.title.to_string(),
                understand_text: safety_eval.message.clone(),
                care_text: safety_eval.action_advice.clone(),
                watch_text: "Monitor vital signs and progression closely.".to_string(),
                escalate_text:
                    "Please contact emergency healthcare services or your doctor immediately."
                        .to_string(),
                safety_alert: Some(safety_eval),
                source: "Deterministic Safety Engine".to_string(),
            },
            detected_symptoms: vec![],
            detected_flow: None,
            detected_energy: None,
            detected_mood: None,
            pain_intensity: 0,
            gentle_progress_coins_awarded: 0,
            economy_intent: None,
            payment_intent: None,
            is_clarification_needed: false,
            clarification_message: None,
            tied_down_choices: vec![],
            raw_query: trimmed.to_string(),
        };
    }
    let local_result = parse_deterministically(trimmed, &safety_eval, formatted_coin_balance);
    if (!local_result.is_clarification_needed && !force_ai)
        || matches!(remote, RemoteAttempt::NotAllowed)
    {
        return local_result;
    }
    let guided = match remote {
        RemoteAttempt::Guided(g) => g,
        RemoteAttempt::NotAllowed => unreachable!(),
    };
    if is_remote_care_response(&guided.source) || force_ai {
        return LayeredChatResult {
            main_response: guided,
            is_clarification_needed: false,
            clarification_message: None,
            tied_down_choices: vec![],
            ..local_result
        };
    }
    local_result
}

fn parse_deterministically(
    input: &str,
    safety_eval: &SafetyEvaluation,
    formatted_coin_balance: &str,
) -> LayeredChatResult {
    let lower = input.to_lowercase();

    // Economy intents - exact keyword chains, first match wins.
    let is_balance_query = lower.contains("how many coins")
        || lower.contains("my balance")
        || lower.contains("coin balance")
        || lower.contains("how many coin")
        || lower.contains("check coins");
    let is_shop_query = lower.contains("charm")
        || lower.contains("artifact")
        || lower.contains("frame")
        || lower.contains("store")
        || lower.contains("shop")
        || lower.contains("buy artifact")
        || lower.contains("moon");
    let is_transfer_query = lower.contains("transfer")
        || lower.contains("send coin")
        || lower.contains("give coin");
    let economy_intent = if is_balance_query {
        Some(EconomyChatIntent::BalanceInquiry)
    } else if is_shop_query {
        Some(EconomyChatIntent::ArtifactCatalog)
    } else if is_transfer_query {
        Some(EconomyChatIntent::TransferInquiry)
    } else {
        None
    };

    // Payment intents.
    let is_mpesa_query = lower.contains("mpesa")
        || lower.contains("m-pesa")
        || lower.contains("stk")
        || lower.contains("top up")
        || lower.contains("buy coins")
        || lower.contains("49")
        || lower.contains("99")
        || lower.contains("kes")
        || lower.contains("pay");
    let is_pending_query = lower.contains("failed")
        || lower.contains("pending")
        || lower.contains("not received");
    let payment_intent = if is_pending_query && is_mpesa_query {
        Some(PaymentChatIntent::CheckPending)
    } else if is_mpesa_query {
        Some(PaymentChatIntent::MpesaTopup)
    } else {
        None
    };

    // Health symptoms via the NL parser; personal-note gate identical to the
    // consent proposal gate.
    let health_parse = log_parser::parse(input);
    let has_recognized_health = !health_parse.symptoms_result.is_empty()
        || health_parse.flow_level_result.is_some()
        || health_parse.energy_result.is_some()
        || health_parse.mood_result.is_some();
    let is_personal_note = has_recognized_health && care_policy::is_logging_intent(input);

    let articles = care_knowledge::search(input, None);
    let article = articles.first().copied();
    let has_article = !articles.is_empty();

    if is_personal_note || economy_intent.is_some() || payment_intent.is_some() || has_article {
        let mut summary_text = String::new();
        if economy_intent == Some(EconomyChatIntent::BalanceInquiry) {
            summary_text.push_str(&format!(
                "You currently have {} Cycle Coins in your account. ",
                formatted_coin_balance
            ));
        } else if economy_intent == Some(EconomyChatIntent::ArtifactCatalog) {
            summary_text
                .push_str("Our artifacts and charms can be unlocked using your Cycle Coins. ");
        }
        if payment_intent == Some(PaymentChatIntent::MpesaTopup) {
            summary_text.push_str(MPESA_UNAVAILABLE);
        }
        if summary_text.is_empty() {
            summary_text.push_str(article.map(|a| a.question).unwrap_or(
                "I found possible observations in your note. Review them below before choosing whether to save.",
            ));
        }
        let what_text = if is_personal_note {
            format!(
                "Possible observations found: {}. Review them before choosing whether to save.",
                health_parse
                    .items
                    .iter()
                    .map(|i| i.label)
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        } else {
            article.map(|a| a.what_text.to_string()).unwrap_or_default()
        };
        return LayeredChatResult {
            main_response: CareResponse {
                summary: summary_text,
                what_text,
                understand_text: article.map(|a| a.understand_text.to_string()).unwrap_or_default(),
                care_text: article.map(|a| a.care_text.to_string()).unwrap_or_default(),
                watch_text: article.map(|a| a.watch_text.to_string()).unwrap_or_default(),
                escalate_text: article.map(|a| a.escalate_text.to_string()).unwrap_or_default(),
                safety_alert: alert(safety_eval),
                source: if is_personal_note {
                    "Gentle Health Logger".to_string()
                } else {
                    "Curated Clinical Knowledge".to_string()
                },
            },
            detected_symptoms: if is_personal_note {
                health_parse.symptoms_result.iter().map(|s| s.to_string()).collect()
            } else {
                vec![]
            },
            detected_flow: if is_personal_note {
                health_parse.flow_level_result.map(|s| s.to_string())
            } else {
                None
            },
            detected_energy: if is_personal_note {
                health_parse.energy_result.map(|s| s.to_string())
            } else {
                None
            },
            detected_mood: if is_personal_note {
                health_parse.mood_result.map(|s| s.to_string())
            } else {
                None
            },
            pain_intensity: if is_personal_note {
                health_parse.pain_intensity_result
            } else {
                0
            },
            gentle_progress_coins_awarded: 0,
            economy_intent,
            payment_intent,
            is_clarification_needed: false,
            clarification_message: None,
            tied_down_choices: vec![],
            raw_query: input.to_string(),
        };
    }

    LayeredChatResult {
        main_response: CareResponse {
            summary: "We're here with you".to_string(),
            what_text: "Your message was gently received, but needs a moment of clarity so we can assist you accurately.".to_string(),
            understand_text: "Every body is unique. Sometimes words overlap between symptoms, questions, and rewards.".to_string(),
            care_text: "Take a calm breath. Select one of the guided options below to help us understand right away.".to_string(),
            watch_text: "If you are experiencing acute physical pain or unusual heavy bleeding, seek clinical help immediately.".to_string(),
            escalate_text: "Never hesitate to consult a doctor for urgent symptoms.".to_string(),
            safety_alert: alert(safety_eval),
            source: "Care Concierge".to_string(),
        },
        detected_symptoms: vec![],
        detected_flow: None,
        detected_energy: None,
        detected_mood: None,
        pain_intensity: 0,
        gentle_progress_coins_awarded: 0,
        economy_intent: None,
        payment_intent: None,
        is_clarification_needed: true,
        clarification_message: Some("I want to understand you accurately. Is your message mainly about how your body feels, cycle timing, or rewards? Choose the closest option below, or leave a note to return to later.".to_string()),
        tied_down_choices: vec![
            TiedDownChoice { id: "choice_symptoms", icon_emoji: "🩸", label: "Log symptoms or how my body feels", prefilled_query: "I want to log my symptoms and energy today" },
            TiedDownChoice { id: "choice_coins", icon_emoji: "🪙", label: "Check Cycle Coins & Rewards", prefilled_query: "How many coins do I have and what can I unlock?" },
            TiedDownChoice { id: "choice_mpesa", icon_emoji: "📱", label: "M-Pesa payment or coin top-up", prefilled_query: "I need help with M-Pesa coin package checkout" },
            TiedDownChoice { id: "choice_advice", icon_emoji: "🌿", label: "Cycle phase or period advice", prefilled_query: "What does my current cycle phase mean for my body?" },
        ],
        raw_query: input.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::care_ai::{consult_decide, CloudOutcome, ConsultInput};

    /// Rebuilds the probe's sig JSON and compares field by field.
    fn check(r: &LayeredChatResult, exp: &str) {
        let v: std::collections::HashMap<String, String> =
            match crate::sync_codec::JsonParser::new(exp).parse().unwrap() {
                crate::sync_codec::JsonValue::Object(pairs) => pairs.into_iter().map(|(k, v)| match v {
                    crate::sync_codec::JsonValue::Str(s) => (k, s),
                    crate::sync_codec::JsonValue::Int(i) => (k, i.to_string()),
                    crate::sync_codec::JsonValue::Bool(b) => (k, b.to_string()),
                    _ => (k, String::new()),
                }).collect(),
                _ => panic!("expected object"),
            };
        let g = |k: &str| v.get(k).cloned().unwrap_or_default();
        let m = &r.main_response;
        assert_eq!(m.summary, g("summary"), "summary");
        assert_eq!(m.what_text, g("whatText"), "whatText");
        assert_eq!(m.understand_text, g("understandText"), "understandText");
        assert_eq!(m.care_text, g("careText"), "careText");
        assert_eq!(m.watch_text, g("watchText"), "watchText");
        assert_eq!(m.escalate_text, g("escalateText"), "escalateText");
        assert_eq!(m.source, g("source"), "source");
        let lvl = g("safetyLevel");
        if lvl.is_empty() { assert!(m.safety_alert.is_none(), "safety none"); }
        else { assert_eq!(m.safety_alert.as_ref().unwrap().level.name(), lvl, "safety"); }
        assert_eq!(r.detected_symptoms.join(","), g("symptoms"), "symptoms");
        assert_eq!(r.detected_flow.clone().unwrap_or_default(), g("flow"), "flow");
        assert_eq!(r.detected_energy.clone().unwrap_or_default(), g("energy"), "energy");
        assert_eq!(r.detected_mood.clone().unwrap_or_default(), g("mood"), "mood");
        assert_eq!(r.pain_intensity.to_string(), g("pain"), "pain");
        assert_eq!(r.gentle_progress_coins_awarded.to_string(), g("coins"), "coins");
        assert_eq!(r.economy_intent.map(|e| e.name().to_string()).unwrap_or_default(), g("econ"), "econ");
        assert_eq!(r.payment_intent.map(|p| p.name().to_string()).unwrap_or_default(), g("pay"), "pay");
        assert_eq!(r.is_clarification_needed.to_string(), g("clarify"), "clarify");
        assert_eq!(r.clarification_message.clone().unwrap_or_default(), g("clarifyMsg"), "clarifyMsg");
        assert_eq!(r.tied_down_choices.iter().map(|c| c.id).collect::<Vec<_>>().join(","), g("choices"), "choices");
        assert_eq!(r.raw_query, g("raw"), "raw");
    }

    fn proc(input: &str) -> LayeredChatResult {
        process_raw_chat(input, None, "363", false, RemoteAttempt::NotAllowed)
    }

    #[test]
    fn deterministic_rows_locked() {
        check(&proc("I fainted and my bleeding is unusually heavy"), "{\"summary\": \"Please seek medical help\", \"whatText\": \"Urgent medical attention advised\", \"understandText\": \"Fainting and unusually heavy bleeding were mentioned together.\", \"careText\": \"Please seek urgent medical care now, especially if symptoms are continuing or worsening.\", \"watchText\": \"Monitor vital signs and progression closely.\", \"escalateText\": \"Please contact emergency healthcare services or your doctor immediately.\", \"safetyLevel\": \"URGENT\", \"source\": \"Deterministic Safety Engine\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"I fainted and my bleeding is unusually heavy\"}"); // l01
        check(&proc("how many coins do I have?"), "{\"summary\": \"You currently have 363 Cycle Coins in your account. \", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"BALANCE_INQUIRY\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"how many coins do I have?\"}"); // l02
        check(&proc("show me the artifact store"), "{\"summary\": \"Our artifacts and charms can be unlocked using your Cycle Coins. \", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"ARTIFACT_CATALOG\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"show me the artifact store\"}"); // l03
        check(&proc("can I transfer coins to a friend"), "{\"summary\": \"I found possible observations in your note. Review them below before choosing whether to save.\", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"TRANSFER_INQUIRY\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"can I transfer coins to a friend\"}"); // l04
        check(&proc("help with mpesa top up"), "{\"summary\": \"M-Pesa checkout is currently unavailable while payment safety review is incomplete. Do not send a payment. \", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"MPESA_TOPUP\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"help with mpesa top up\"}"); // l05
        check(&proc("my mpesa payment failed and coins not received"), "{\"summary\": \"I found possible observations in your note. Review them below before choosing whether to save.\", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"CHECK_PENDING\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"my mpesa payment failed and coins not received\"}"); // l06
        check(&proc("my cramps are severe today"), "{\"summary\": \"How to ease pelvic cramps and period pain?\", \"whatText\": \"Aching or throbbing contractions in the lower abdomen or pelvic basin right before and during menstruation.\", \"understandText\": \"The uterine lining produces prostaglandins, chemical messengers that induce uterine muscle contractions to shed the endometrium. Higher prostaglandin levels equal more intense cramps.\", \"careText\": \"Apply a warm heating pad or hot water bottle to the lower abdomen (heat may provide comfort for some people). Sip warm ginger or chamomile tea, and practice gentle pelvic rocking.\", \"watchText\": \"Keep track of how long cramps last. Typical primary dysmenorrhea peaks on days 1-2 and recedes significantly by day 3.\", \"escalateText\": \"Seek medical advice if pain is so severe you vomit or miss work, radiates sharply down your legs, or starts worsening after age 25 (possible endometriosis or fibroids).\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"my cramps are severe today\"}"); // l07
        check(&proc("How can I ease period cramps?"), "{\"summary\": \"How to ease pelvic cramps and period pain?\", \"whatText\": \"Aching or throbbing contractions in the lower abdomen or pelvic basin right before and during menstruation.\", \"understandText\": \"The uterine lining produces prostaglandins, chemical messengers that induce uterine muscle contractions to shed the endometrium. Higher prostaglandin levels equal more intense cramps.\", \"careText\": \"Apply a warm heating pad or hot water bottle to the lower abdomen (heat may provide comfort for some people). Sip warm ginger or chamomile tea, and practice gentle pelvic rocking.\", \"watchText\": \"Keep track of how long cramps last. Typical primary dysmenorrhea peaks on days 1-2 and recedes significantly by day 3.\", \"escalateText\": \"Seek medical advice if pain is so severe you vomit or miss work, radiates sharply down your legs, or starts worsening after age 25 (possible endometriosis or fibroids).\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"How can I ease period cramps?\"}"); // l08
        check(&proc("zzq qqzz blorp"), "{\"summary\": \"We're here with you\", \"whatText\": \"Your message was gently received, but needs a moment of clarity so we can assist you accurately.\", \"understandText\": \"Every body is unique. Sometimes words overlap between symptoms, questions, and rewards.\", \"careText\": \"Take a calm breath. Select one of the guided options below to help us understand right away.\", \"watchText\": \"If you are experiencing acute physical pain or unusual heavy bleeding, seek clinical help immediately.\", \"escalateText\": \"Never hesitate to consult a doctor for urgent symptoms.\", \"safetyLevel\": \"REVIEW\", \"source\": \"Care Concierge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": true, \"clarifyMsg\": \"I want to understand you accurately. Is your message mainly about how your body feels, cycle timing, or rewards? Choose the closest option below, or leave a note to return to later.\", \"choices\": \"choice_symptoms,choice_coins,choice_mpesa,choice_advice\", \"raw\": \"zzq qqzz blorp\"}"); // l09
        check(&proc("how many coins can I spend in the shop"), "{\"summary\": \"You currently have 363 Cycle Coins in your account. \", \"whatText\": \"\", \"understandText\": \"\", \"careText\": \"\", \"watchText\": \"\", \"escalateText\": \"\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"BALANCE_INQUIRY\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"how many coins can I spend in the shop\"}"); // l10
        check(&proc("why do I feel bloated before my period"), "{\"summary\": \"Why am I so tired before my period?\", \"whatText\": \"A noticeable decline in physical stamina and mental focus in the 3 to 7 days before menstruation.\", \"understandText\": \"After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.\", \"careText\": \"Consider magnesium-containing foods if they suit you, prioritize rest, and choose gentler movement if intense exercise feels difficult.\", \"watchText\": \"Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.\", \"escalateText\": \"Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance).\", \"safetyLevel\": \"REVIEW\", \"source\": \"Curated Clinical Knowledge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"why do I feel bloated before my period\"}"); // l11
    }

    #[test]
    fn enrichment_rows_locked() {
        // l12: forceAi=true, allowRemote+token; consult fell back locally
        // (stub network) -> not remote -> forceAi swap of mainResponse only.
        let guided12 = consult_decide(&ConsultInput {
            user_question: "zzq qqzz blorp", recent_log: None, force_ai: true,
            ai_sharing_allowed: true, cloud: CloudOutcome::NotAttempted,
        });
        check(&process_raw_chat("zzq qqzz blorp", None, "363", true, RemoteAttempt::Guided(guided12)), "{\"summary\": \"Deeper explanation unavailable\", \"whatText\": \"An online explanation could not be completed. No health entry was saved from this conversation.\", \"understandText\": \"An app cannot diagnose symptoms or confirm ovulation from a calendar estimate.\", \"careText\": \"You can rephrase your question or use the local tracking and care guidance.\", \"watchText\": \"Notice symptoms that are new, persistent or worsening.\", \"escalateText\": \"Seek urgent medical help for severe, sudden or worsening symptoms.\", \"safetyLevel\": \"REVIEW\", \"source\": \"Local guidance · online explanation unavailable\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": false, \"clarifyMsg\": \"\", \"choices\": \"\", \"raw\": \"zzq qqzz blorp\"}"); // l12
        // l13: same but forceAi=false -> local CASE B result kept verbatim.
        let guided13 = consult_decide(&ConsultInput {
            user_question: "zzq qqzz blorp", recent_log: None, force_ai: false,
            ai_sharing_allowed: true, cloud: CloudOutcome::NotAttempted,
        });
        check(&process_raw_chat("zzq qqzz blorp", None, "363", false, RemoteAttempt::Guided(guided13)), "{\"summary\": \"We're here with you\", \"whatText\": \"Your message was gently received, but needs a moment of clarity so we can assist you accurately.\", \"understandText\": \"Every body is unique. Sometimes words overlap between symptoms, questions, and rewards.\", \"careText\": \"Take a calm breath. Select one of the guided options below to help us understand right away.\", \"watchText\": \"If you are experiencing acute physical pain or unusual heavy bleeding, seek clinical help immediately.\", \"escalateText\": \"Never hesitate to consult a doctor for urgent symptoms.\", \"safetyLevel\": \"REVIEW\", \"source\": \"Care Concierge\", \"symptoms\": \"\", \"flow\": \"\", \"energy\": \"\", \"mood\": \"\", \"pain\": 0, \"coins\": 0, \"econ\": \"\", \"pay\": \"\", \"clarify\": true, \"clarifyMsg\": \"I want to understand you accurately. Is your message mainly about how your body feels, cycle timing, or rewards? Choose the closest option below, or leave a note to return to later.\", \"choices\": \"choice_symptoms,choice_coins,choice_mpesa,choice_advice\", \"raw\": \"zzq qqzz blorp\"}"); // l13
    }

    #[test]
    fn remote_success_swap_rules() {
        // A remote-sourced guided response swaps in and clears clarification
        // even without forceAi (isRemoteCareResponse branch).
        let guided = crate::care_ai::CareResponse {
            summary: "s".into(), what_text: "w".into(), understand_text: "u".into(),
            care_text: "c".into(), watch_text: "wa".into(), escalate_text: "e".into(),
            safety_alert: None, source: crate::care_ai::REMOTE_CARE_SOURCE.to_string(),
        };
        let r = process_raw_chat("zzq qqzz blorp", None, "363", false, RemoteAttempt::Guided(guided));
        assert_eq!(r.main_response.source, "CycleCare Assistant");
        assert!(!r.is_clarification_needed);
        assert!(r.clarification_message.is_none());
        assert!(r.tied_down_choices.is_empty());
        // Non-remote guided source without forceAi keeps the local result.
        let guided_local = crate::care_ai::CareResponse {
            summary: "s".into(), what_text: "w".into(), understand_text: "u".into(),
            care_text: "c".into(), watch_text: "wa".into(), escalate_text: "e".into(),
            safety_alert: None, source: "Local guidance · online explanation unavailable".into(),
        };
        let r2 = process_raw_chat("zzq qqzz blorp", None, "363", false, RemoteAttempt::Guided(guided_local));
        assert_eq!(r2.main_response.source, "Care Concierge");
        assert!(r2.is_clarification_needed);
        assert_eq!(r2.tied_down_choices.len(), 4);
    }
}