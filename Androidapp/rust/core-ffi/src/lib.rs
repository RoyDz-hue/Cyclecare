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
use std::str::FromStr;
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
        "startup_gate" => {
            let b = |k: &str| v[k].as_bool().unwrap_or(false);
            let g = cyclecare_core::lifecycle::startup_gate(
                b("local"), b("secure"), b("stored"), b("profile"), b("onboardingCompleted"),
                b("pinEnabled"), b("pinPresent"), b("fingerprintEnabled"), b("userIdPresent"),
            );
            serde_json::json!({"ok": {"hasCompletedInitialAuth": g.has_completed_initial_auth,
                "needsCloudProfileCheck": g.needs_cloud_profile_check, "isLocked": g.is_locked,
                "showOnboarding": g.show_onboarding, "fetchWallet": g.fetch_wallet}}).to_string()
        }
        "effective_user_id" => {
            let uid = v["userId"].as_str();
            let device = v["deviceId"].as_str().unwrap_or("");
            serde_json::json!({"ok": cyclecare_core::lifecycle::effective_user_id(uid, device)}).to_string()
        }
        "ensure_setup_ok" => {
            let body = v["body"].as_str().unwrap_or("");
            serde_json::json!({"ok": cyclecare_core::verified_profile::ensure_setup_ok(body)}).to_string()
        }
        "has_remote_profile" => {
            let r = cyclecare_core::reconcile::has_remote_profile_response(
                v["httpOk"].as_bool().unwrap_or(false),
                v["body"].as_str().unwrap_or(""),
                v["owner"].as_str().unwrap_or(""),
            );
            match r {
                Some(b) => serde_json::json!({"ok": b}).to_string(),
                None => serde_json::json!({"ok": serde_json::Value::Null}).to_string(),
            }
        }
        "verified_profile_parse" => {
            match cyclecare_core::verified_profile::parse_verified_profile(
                v["owner"].as_str().unwrap_or(""),
                v["econBody"].as_str().unwrap_or(""),
                v["profBody"].as_str().unwrap_or(""),
                v["nowIso"].as_str().unwrap_or(""),
            ) {
                // Nullable string fields emit "" (never JSON null): the Kotlin
                // consumer applies optString(...).ifBlank { null }, and AOSP
                // optString renders JSON null as the literal "null" STRING
                // (OptStringNullProbe on AOSP14 classes). "" is the only value
                // that round-trips to production null through that consumer.
                Ok(p) => serde_json::json!({"ok": {"userId": p.user_id, "tier": p.tier, "rank": p.rank,
                    "coinBalance": p.coin_balance, "lifetimeEarned": p.lifetime_earned,
                    "lifetimeSpent": p.lifetime_spent, "activeFrame": p.active_frame.unwrap_or_default(),
                    "activeArtifact": p.active_artifact, "activeAvatarArt": p.active_avatar_art.unwrap_or_default(),
                    "activeTheme": p.active_theme, "lastMinedDate": p.last_mined_date.unwrap_or_default(),
                    "unlockedItemIds": p.unlocked_item_ids, "status": p.status,
                    "suspensionReason": p.suspension_reason.unwrap_or_default(), "suspendedUntil": p.suspended_until.unwrap_or_default(),
                    "giftedArtifacts": p.gifted_artifacts}}).to_string(),
                Err(m) => err(&m),
            }
        }
        "reconcile_offline_claim" => {
            let bd = |k: &str| bigdecimal::BigDecimal::from_str(v[k].as_str().unwrap_or("0")).unwrap_or_default();
            let o = cyclecare_core::reconcile::reconcile_offline_claim_response(
                v["httpOk"].as_bool().unwrap_or(false),
                v["body"].as_str().unwrap_or(""),
                &bd("balance"), &bd("earned"),
                v["rank"].as_str().unwrap_or("LUMEN"),
                v["pendingDate"].as_str().unwrap_or(""),
            );
            use cyclecare_core::reconcile::ReconcileOutcome as R;
            let body = match o {
                R::Applied { coin_balance, lifetime_earned, rank, mined_date } => serde_json::json!({
                    "outcome": "APPLIED", "coinBalance": coin_balance,
                    "lifetimeEarned": lifetime_earned, "rank": rank, "minedDate": mined_date}),
                R::AlreadyRecorded => serde_json::json!({"outcome": "ALREADY_RECORDED"}),
                R::Refetch => serde_json::json!({"outcome": "REFETCH"}),
                R::OfflineKept => serde_json::json!({"outcome": "OFFLINE_KEPT"}),
            };
            serde_json::json!({"ok": body}).to_string()
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

#[cfg(test)]
mod tests {
    // Envelope smoke checks reuse the executed JVM expectations (LifecycleParity
    // gate_returning_complete; VerifiedProfileParity vp01; ReconcileParity rc01,
    // hr01, es01).
    #[test]
    fn startup_gate_contract_matches_executed() {
        let r = super::dispatch("startup_gate",
            r#"{"local":false,"secure":true,"stored":true,"profile":true,"onboardingCompleted":true,"pinEnabled":false,"pinPresent":false,"fingerprintEnabled":false,"userIdPresent":true}"#);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"]["hasCompletedInitialAuth"], true);
        assert_eq!(v["ok"]["needsCloudProfileCheck"], false);
        assert_eq!(v["ok"]["isLocked"], false);
        assert_eq!(v["ok"]["showOnboarding"], false);
        assert_eq!(v["ok"]["fetchWallet"], true);
    }

    #[test]
    fn verified_profile_contract_matches_vp01() {
        let payload = serde_json::json!({
            "owner": "u1",
            "econBody": "[{\"user_id\":\"u1\",\"status\":\"active\",\"coin_balance\":363.5,\"lifetime_earned\":1000,\"lifetime_spent\":10,\"tier\":\"supporter\",\"rank\":\"aurelia\",\"active_frame\":\"f1\",\"active_avatar_art\":\"a1\",\"last_mined_date\":\"2026-11-01\",\"unlocked_items\":[\"custom_x\"],\"artifacts\":[{\"id\":\"gift1\"}]}]",
            "profBody": "{\"user_id\":\"u1\",\"status\":\"ACTIVE\"}",
            "nowIso": "2026-11-02T10:00:00Z"
        }).to_string();
        let r = super::dispatch("verified_profile_parse", &payload);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"]["tier"], "SUPPORTER");
        assert_eq!(v["ok"]["coinBalance"], "363.5");
        assert_eq!(v["ok"]["lifetimeSpent"], "10");
        assert_eq!(v["ok"]["status"], "ACTIVE");
        assert_eq!(v["ok"]["giftedArtifacts"][0], "gift1");
    }

    #[test]
    fn reconcile_and_remote_contracts_match_executed() {
        let r = super::dispatch("reconcile_offline_claim", &serde_json::json!({
            "httpOk": true,
            "body": "{\"success\":true,\"new_balance\":363.5,\"lifetime_earned\":393.5,\"new_rank\":\"aurelia\",\"mined_date\":\"2026-11-01\"}",
            "balance": "333.5", "earned": "363.5", "rank": "LUMEN", "pendingDate": "2026-10-31"
        }).to_string());
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"]["outcome"], "APPLIED");
        assert_eq!(v["ok"]["coinBalance"], "363.5");
        assert_eq!(v["ok"]["rank"], "AURELIA");
        assert_eq!(v["ok"]["minedDate"], "2026-11-01");

        let r = super::dispatch("has_remote_profile", r#"{"httpOk":true,"body":"{\"user_id\":\"u1\",\"exists\":true}","owner":"u1"}"#);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"], true);
        let r = super::dispatch("has_remote_profile", r#"{"httpOk":true,"body":"{\"user_id\":\"u2\",\"exists\":true}","owner":"u1"}"#);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert!(v["ok"].is_null());

        let r = super::dispatch("ensure_setup_ok", r#"{"body":"{\"success\":\"TRUE\"}"}"#);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"], true);

        let r = super::dispatch("effective_user_id", r#"{"userId":null,"deviceId":"dev-1"}"#);
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["ok"], "1a6fd60a-34f6-39ee-a11b-f9e6b9689441");
    }
}
