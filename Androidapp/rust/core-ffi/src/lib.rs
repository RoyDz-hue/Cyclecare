//! Read-only JNI bridge over cyclecare-core pure functions.
//!
//! Contract with the Kotlin host (com.cyclecare.app.rust.CycleCareCoreBridge):
//!   external fun nativeCoreHandshake(): Int            -> always 42
//!   external fun nativeCall(contract: String, payloadJson: String): String
//! Dispatcher envelope: {"ok": <contract-specific>} or {"error": <message>}.
//! Purity: no DB, no Keystore, no signing, no clock reads (all times are
//! payload inputs), no account mutation. Strings only; no secrets in payloads.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use cyclecare_core::coin_amount::sanitize_coin_amount_input;
use cyclecare_core::cycle_math::{parse_local_date_iso, validate_period_date_strings};
use cyclecare_core::economy_status::{
    self, RestrictionRecord, WalletNumber,
};
use cyclecare_core::pattern_engine::{self, PatternLog, PatternSettings};
use cyclecare_core::pin::is_complete_pin;
use cyclecare_core::reconnect_policy::{self, Decision, ReconnectState};
use cyclecare_core::safety::{self, SafetyLog};

#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeCoreHandshake(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    42
}

#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeCall(
    mut env: JNIEnv,
    _class: JClass,
    contract: JString,
    payload: JString,
) -> jstring {
    let contract: String = match env.get_string(&contract) {
        Ok(s) => s.into(),
        Err(e) => return out(&mut env, &err(&format!("contract not a string: {e}"))),
    };
    let payload: String = match env.get_string(&payload) {
        Ok(s) => s.into(),
        Err(e) => return out(&mut env, &err(&format!("payload not a string: {e}"))),
    };
    let result = std::panic::catch_unwind(|| dispatch(&contract, &payload))
        .unwrap_or_else(|_| err("rust panic caught at FFI boundary".into()));
    out(&mut env, &result)
}

fn out(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn err(msg: &str) -> String {
    serde_json::json!({"error": msg}).to_string()
}

fn dispatch(contract: &str, payload: &str) -> String {
    let v: serde_json::Value = match serde_json::from_str(payload) {
        Ok(v) => v,
        Err(e) => return err(&format!("bad payload json: {e}")),
    };
    match contract {
        "sanitize_coin_amount" => {
            let raw = v["raw"].as_str().unwrap_or("");
            let (text, rejection) = sanitize_coin_amount_input(raw);
            serde_json::json!({"ok": {"text": text, "rejection": rejection}}).to_string()
        }
        "is_complete_pin" => {
            let pin = v["pin"].as_str().unwrap_or("");
            serde_json::json!({"ok": is_complete_pin(pin)}).to_string()
        }
        "parse_local_date_iso" => {
            let s = v["date"].as_str().unwrap_or("");
            let r = parse_local_date_iso(s).map(|d| d.to_string());
            serde_json::json!({"ok": r}).to_string()
        }
        "validate_period_date_strings" => {
            let start = v["start"].as_str();
            let end = v["end"].as_str();
            serde_json::json!({"ok": validate_period_date_strings(start, end)}).to_string()
        }
        "decide_reconnect" => reconnect(v, false),
        "decide_reconnect_trailing" => reconnect(v, true),
        "evaluate_safety" => {
            let log = v["log"].as_object().map(|o| SafetyLog {
                flow_level: o["flowLevel"].as_str().unwrap_or("").into(),
                symptoms_csv: o["symptomsCsv"].as_str().unwrap_or("").into(),
                vaginal_health_csv: o["vaginalHealthCsv"].as_str().unwrap_or("").into(),
                urinary_health_csv: o["urinaryHealthCsv"].as_str().unwrap_or("").into(),
                pain_intensity: o["painIntensity"].as_i64().unwrap_or(0) as i32,
            });
            let ev = safety::evaluate(log.as_ref());
            serde_json::json!({"ok": {"level": format!("{:?}", ev.level), "title": ev.title,
                "message": ev.message, "actionAdvice": ev.action_advice, "isRedFlag": ev.is_red_flag}})
                .to_string()
        }
        "compute_baseline" => {
            let s = &v["settings"];
            let settings = PatternSettings {
                average_cycle_length: s["averageCycleLength"].as_i64().unwrap_or(28) as i32,
                period_duration: s["periodDuration"].as_i64().unwrap_or(5) as i32,
                luteal_phase_days: s["lutealPhaseDays"].as_i64().unwrap_or(14) as i32,
                last_period_start_date: s["lastPeriodStartDate"].as_str().unwrap_or("").into(),
            };
            let logs: Vec<PatternLog> = v["logs"]
                .as_array()
                .map(|a| {
                    a.iter()
                        .map(|l| PatternLog {
                            date_string: l["dateString"].as_str().unwrap_or("").into(),
                            flow_level: l["flowLevel"].as_str().unwrap_or("").into(),
                            symptoms_csv: l["symptomsCsv"].as_str().unwrap_or("").into(),
                        })
                        .collect()
                })
                .unwrap_or_default();
            let m = pattern_engine::compute_baseline(&settings, &logs);
            serde_json::json!({"ok": {"averageCycleLength": m.average_cycle_length,
                "medianCycleLength": m.median_cycle_length, "minCycleLength": m.min_cycle_length,
                "maxCycleLength": m.max_cycle_length, "variabilityDays": m.variability_days,
                "isRegular": m.is_regular, "totalLoggedDays": m.total_logged_days,
                "observedCycleIntervals": m.observed_cycle_intervals}})
                .to_string()
        }
        "verified_coin_amount" => {
            let field = v["field"].as_str().unwrap_or("value");
            let num = match &v["value"] {
                serde_json::Value::Number(n) => {
                    if let Some(i) = n.as_i64() {
                        WalletNumber::Integer(i)
                    } else if let Some(f) = n.as_f64() {
                        WalletNumber::Decimal(f)
                    } else {
                        WalletNumber::NotANumber
                    }
                }
                _ => WalletNumber::NotANumber,
            };
            match economy_status::verified_coin_amount(field, num) {
                Ok(text) => serde_json::json!({"ok": text}).to_string(),
                Err(e) => err(&e),
            }
        }
        "merge_restrictions" => {
            let now = match v["now"].as_str().and_then(|s| {
                chrono::DateTime::parse_from_rfc3339(s).ok()
            }) {
                Some(dt) => dt.with_timezone(&chrono::Utc),
                None => return err("now must be RFC3339"),
            };
            let owned: Vec<RestrictionRecord> = v["records"]
                .as_array()
                .map(|a| {
                    a.iter()
                        .map(|r| RestrictionRecord {
                            status: r["status"].as_str().unwrap_or("").into(),
                            suspension_reason: r["suspensionReason"].as_str().map(Into::into),
                            suspended_until: r["suspendedUntil"].as_str().map(Into::into),
                        })
                        .collect()
                })
                .unwrap_or_default();
            let refs: Vec<&RestrictionRecord> = owned.iter().collect();
            let m = economy_status::merge_restrictions(&refs, now);
            serde_json::json!({"ok": {"activeStatus": m.active_status,
                "finalReason": m.final_reason, "finalUntil": m.final_until}})
                .to_string()
        }
        other => err(&format!("unknown contract: {other}")),
    }
}

fn reconnect(v: serde_json::Value, trailing: bool) -> String {
    let st = &v["state"];
    let mut state = ReconnectState {
        was_connected: st["wasConnected"].as_bool().unwrap_or(true),
        last_run_at_ms: st["lastRunAtMs"].as_i64().unwrap_or(0),
        owner: st["owner"].as_str().map(Into::into),
    };
    let is_connected = v["isConnected"].as_bool().unwrap_or(false);
    let is_eligible = v["isEligible"].as_bool().unwrap_or(false);
    let owner = v["currentOwner"].as_str();
    let now_ms = v["nowMs"].as_i64().unwrap_or(0);
    let d = if trailing {
        reconnect_policy::decide_trailing(&mut state, is_connected, is_eligible, owner, now_ms)
    } else {
        reconnect_policy::decide(&mut state, is_connected, is_eligible, owner, now_ms)
    };
    let dj = match d {
        Decision::Run => serde_json::json!({"kind": "Run"}),
        Decision::Skip => serde_json::json!({"kind": "Skip"}),
        Decision::Throttled { retry_after_ms } => {
            serde_json::json!({"kind": "Throttled", "retryAfterMs": retry_after_ms})
        }
    };
    serde_json::json!({"ok": dj, "state": {"wasConnected": state.was_connected,
        "lastRunAtMs": state.last_run_at_ms, "owner": state.owner}})
        .to_string()
}

#[cfg(test)]
mod host_dispatcher_tests {
    #[test]
    fn handshake_and_contracts() {
        let v: serde_json::Value = serde_json::from_str(&super::dispatch("sanitize_coin_amount", r#"{"raw":" 12.5 "}"#)).unwrap();
        assert_eq!(v["ok"]["text"], "12.5");
        assert!(v["ok"]["rejection"].is_null());
        assert!(super::dispatch("is_complete_pin", r#"{"pin":"1234"}"#).contains("true"));
        assert_eq!(super::dispatch("parse_local_date_iso", r#"{"date":"2026-1-5"}"#), r#"{"ok":null}"#);
        let v: serde_json::Value = serde_json::from_str(&super::dispatch("parse_local_date_iso", r#"{"date":"2026-01-05"}"#)).unwrap();
        assert_eq!(v["ok"], "2026-01-05");
        let v: serde_json::Value = serde_json::from_str(&super::dispatch("verified_coin_amount", r#"{"field":"coin_balance","value":363.0}"#)).unwrap();
        assert_eq!(v["ok"], "363.0");
        let r = super::dispatch("decide_reconnect",
            r#"{"state":{"wasConnected":false,"lastRunAtMs":10000,"owner":"o"},"isConnected":true,"isEligible":true,"currentOwner":"o","nowMs":5000}"#);
        assert!(r.contains("Throttled") && r.contains("65000"), "{r}");
        let r = super::dispatch("evaluate_safety", r#"{"log":{"flowLevel":"UNUSUALLY_HEAVY","symptomsCsv":"","vaginalHealthCsv":"","urinaryHealthCsv":"","painIntensity":0}}"#);
        assert!(r.contains("SeekProfessionalAdvice") && r.contains("isRedFlag\":true"), "{r}");
        let r = super::dispatch("nope", "{}");
        assert!(r.contains("unknown contract"), "{r}");
    }
}
