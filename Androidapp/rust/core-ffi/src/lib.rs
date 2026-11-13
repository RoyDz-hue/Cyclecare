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

use corelib::coin_amount::sanitize_coin_amount_input;
use corelib::cycle_math::{parse_local_date_iso, validate_period_date_strings};
use corelib::economy_status::{
    self, RestrictionRecord, WalletNumber,
};
use corelib::pattern_engine::{self, PatternLog, PatternSettings};
use corelib::pin::is_complete_pin;
use corelib::pin_verify::{verify_pin, encode_pin_pbkdf2_with_salt, PinVerify};
use corelib::reconnect_policy::{self, Decision, ReconnectState};
use std::str::FromStr;
use corelib::safety::{self, SafetyLog};

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

fn prediction_json(p: &corelib::cycle_math::DayPrediction) -> serde_json::Value {
    serde_json::json!({
        "date": p.date.to_string(),
        "dayOfCycle": p.day_of_cycle,
        "phase": p.phase.kotlin_name(), // Kotlin enum entry name (not Debug)
        "phaseTitle": p.phase.kotlin_title(),
        "phaseSubtitle": p.phase.kotlin_subtitle(),
        "phaseDescription": p.phase.kotlin_description(),
        "phaseAdvice": p.phase.kotlin_advice(),
        "phaseColorHex": p.phase.kotlin_color_hex(),
        "isPeriod": p.is_period,
        "isFertile": p.is_fertile,
        "isOvulation": p.is_ovulation,
        "fertilityConfidence": p.fertility_confidence,
        "pregnancyChance": p.pregnancy_chance,
    })
}

fn err(msg: &str) -> String {
    serde_json::json!({"error": msg}).to_string()
}

fn dispatch(contract: &str, payload: &str) -> String {
    if contract=="local_plan_wire_v1" {return match corelib::local_plan_wire::plan(payload){Ok(plan)=>serde_json::json!({"ok":plan}).to_string(),Err(e)=>err(&e)}}
    let v: serde_json::Value = match serde_json::from_str(payload) {
        Ok(v) => v,
        Err(e) => return err(&format!("bad payload json: {e}")),
    };
    match contract {
        "onboarding_completion_profile_v1" => {
            let i:corelib::onboarding_completion_full::Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("completion input schema")};
            serde_json::json!({"ok":{"version":1,"profile":corelib::onboarding_completion_full::profile(i)}}).to_string()
        }
        "onboarding_completion_settings_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]struct Input {profile:corelib::onboarding_completion_full::Profile,lock:bool}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("completion patch schema")};
            serde_json::json!({"ok":{"version":1,"patch":corelib::onboarding_completion_full::patch(i.profile,i.lock)}}).to_string()
        }
        "onboarding_derived_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==2&&o.contains_key("op")&&o.contains_key("evidence")){return err("derived fields")}
            let op=match v["op"].as_str(){Some(v)=>v,None=>return err("derived op")};
            match corelib::onboarding_derived_full::query(op,&v["evidence"]){Some(ok)=>serde_json::json!({"ok":ok}).to_string(),None=>err("derived schema")}
        }
        "onboarding_date_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==9&&["state","action","date","text","days","ongoing","effectiveStart","calculatedEnd","today"].iter().all(|k|o.contains_key(*k))){return err("onboarding date fields")}
            if !v["state"].as_object().map_or(false,|o|o.len()==10&&["exact","end","chip","duration","option","ongoing","datePicker","endPicker","confirmed","dialog"].iter().all(|k|o.contains_key(*k))){return err("onboarding date state fields")}
            let i:corelib::onboarding_date_full::Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("onboarding date schema")};
            match corelib::onboarding_date_full::reduce(i){Some(state)=>serde_json::json!({"ok":{"version":1,"state":state}}).to_string(),None=>err("onboarding date rejected")}
        }
        "onboarding_bank_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)] struct Input {step:i32,goals:String,earlier:String}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("onboarding bank schema")};
            serde_json::json!({"ok":{"version":1,"questions":corelib::onboarding_questions_full::bank(i.step,&i.goals,&i.earlier)}}).to_string()
        }
        "onboarding_questions_state_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==5&&["entries","step","incoming","action","loading"].iter().all(|k|o.contains_key(*k))){return err("question state fields")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)] struct Input {entries:Vec<corelib::onboarding_questions_full::Entry>,step:i32,incoming:Option<Vec<corelib::onboarding_questions_full::Question>>,action:String,loading:bool}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("question state schema")};
            match corelib::onboarding_questions_full::publish(i.entries,i.step,i.incoming,&i.action){Some((entries,loading))=>serde_json::json!({"ok":{"version":1,"entries":entries,"loading":if i.action=="LOCAL" {i.loading}else{loading}}}).to_string(),None=>err("question state rejected")}
        }
        "onboarding_state_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==2&&o.contains_key("state")&&o.contains_key("event")){return err("onboarding fields mismatch")}
            // Missing optional fields are not silently defaulted by serde.
            if !v["state"].as_object().map_or(false,|o|o.len()==21 && ["step","total","goals","answers","symptoms","energy","sleep","cycleOption","cycleDays","notSure","datePicker","endPicker","remind","age","terms","cloud","termsDialog","privacyDialog","deep","endConfirmed","dateDialog"].iter().all(|k|o.contains_key(*k))){return err("onboarding state mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {state:corelib::onboarding_state_full::State,event:corelib::onboarding_state_full::Event}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("onboarding schema mismatch")};
            match corelib::onboarding_state_full::reduce(i.state,i.event){Some(state)=>serde_json::json!({"ok":{"version":1,"state":state}}).to_string(),None=>err("onboarding reducer rejected")}
        }
        "period_episode_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==5 && ["start","end","today","ongoing","typical"].iter().all(|k|o.contains_key(*k))){return err("period episode fields mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {start:Option<i64>,end:Option<i64>,today:i64,ongoing:bool,typical:i32}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("period episode schema mismatch")};
            // java.time.LocalDate supported full domain, not chrono's narrow years.
            let valid_day=|v:i64|v>=-365243219162 && v<=365241780471;
            if !valid_day(i.today)||!i.start.map_or(true,valid_day)||!i.end.map_or(true,valid_day){return err("period epoch day outside LocalDate")}
            let d=corelib::period_episode_full::decide(i.start,i.end,i.today,i.ongoing,i.typical);
            serde_json::json!({"ok":{"version":1,"valid":d.valid,"error":d.error,"warning":d.warning,"duration":d.duration,"typical":d.typical,"confirm":d.confirm,"keepEnd":d.keep_end}}).to_string()
        }
        "care_merge_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==9 && ["flow","energy","mood","pain","newFlow","newEnergy","newMood","newPain","tokens"].iter().all(|k|o.contains_key(*k))){return err("care merge fields mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)] #[serde(rename_all="camelCase")]
            struct Input {flow:String,energy:String,mood:String,pain:i32,new_flow:Option<String>,new_energy:Option<String>,new_mood:Option<String>,new_pain:i32,tokens:Vec<String>}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("care merge schema mismatch")};
            let p=corelib::care_merge_full::merge(i.flow,i.energy,i.mood,i.pain,i.new_flow,i.new_energy,i.new_mood,i.new_pain,i.tokens);
            serde_json::json!({"ok":{"version":1,"flow":p.flow,"energy":p.energy,"mood":p.mood,"pain":p.pain,"symptoms":p.symptoms}}).to_string()
        }
        "care_context_full_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==5 && ["question","anchor","length","logs","today"].iter().all(|k|o.contains_key(*k))){return err("care context fields mismatch")}
            if !v["logs"].as_array().map_or(false,|logs|logs.iter().all(|l|l.as_object().map_or(false,|o|o.len()==9 && ["date","flow","energy","mood","symptoms","bits","sleep","quality","pain"].iter().all(|k|o.contains_key(*k))))){return err("care context log fields mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,flow:Option<String>,energy:Option<String>,mood:Option<String>,symptoms:Option<String>,bits:String,sleep:String,quality:Option<String>,pain:i32}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {question:String,anchor:Option<String>,length:i32,logs:Vec<Log>,today:String}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("care context schema mismatch")};
            let mut logs=Vec::new();for l in i.logs {if l.bits.len()!=8 || !l.bits.bytes().all(|b|b.is_ascii_hexdigit()){return err("care sleep bits mismatch")};let bits=match u32::from_str_radix(&l.bits,16){Ok(b)=>b,Err(_)=>return err("care sleep bits mismatch")};logs.push(corelib::care_context_full::Log{date:l.date,flow:l.flow,energy:l.energy,mood:l.mood,symptoms:l.symptoms,sleep_bits:bits,sleep_text:l.sleep,quality:l.quality,pain:l.pain});}
            match corelib::care_context_full::compile(&i.question,i.anchor.as_deref(),i.length,&logs,&i.today) {Some(text)=>serde_json::json!({"ok":{"version":1,"text":text}}).to_string(),None=>err("care context unsupported date")}
        }
        "account_start_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==3 && ["valid","state","remote"].iter().all(|k|o.contains_key(*k))){return err("account start fields mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {valid:bool,state:String,remote:Option<bool>}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("account start schema mismatch")};
            let state=match i.state.as_str(){"CLEAN"=>corelib::account_start::LocalDataState::Clean,"CONFLICTED"=>corelib::account_start::LocalDataState::Conflicted,"UNKNOWN"=>corelib::account_start::LocalDataState::Unknown,_=>return err("unknown local state")};
            let d=corelib::account_start::decide(i.valid,state,i.remote);
            let route=match d {corelib::account_start::AccountStartDestination::Home=>"HOME",corelib::account_start::AccountStartDestination::Onboarding=>"ONBOARDING",corelib::account_start::AccountStartDestination::Retry=>"RETRY"};
            serde_json::json!({"ok":{"version":1,"destination":route}}).to_string()
        }
        "reconnect_state_v1" => {
            if !v.as_object().map_or(false,|o|o.len()==6 && ["state","trailing","connected","eligible","owner","now"].iter().all(|k|o.contains_key(*k))){return err("reconnect fields mismatch")}
            if !v["state"].as_object().map_or(false,|o|o.len()==3 && ["connected","last","owner"].iter().all(|k|o.contains_key(*k))){return err("reconnect prestate fields mismatch")}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct State {connected:bool,last:i64,owner:Option<String>}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {state:State,trailing:bool,connected:bool,eligible:bool,owner:Option<String>,now:i64}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("reconnect state schema mismatch")};
            let mut state=ReconnectState{was_connected:i.state.connected,last_run_at_ms:i.state.last,owner:i.state.owner};
            let d=if i.trailing {reconnect_policy::decide_trailing(&mut state,i.connected,i.eligible,i.owner.as_deref(),i.now)} else {reconnect_policy::decide(&mut state,i.connected,i.eligible,i.owner.as_deref(),i.now)};
            let (kind,wait)=match d {Decision::Run=>("RUN",None),Decision::Skip=>("SKIP",None),Decision::Throttled{retry_after_ms}=>("THROTTLED",Some(retry_after_ms))};
            serde_json::json!({"ok":{"version":1,"kind":kind,"wait":wait,"state":{"connected":state.was_connected,"last":state.last_run_at_ms,"owner":state.owner}}}).to_string()
        }
        "recovery_after_pass_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {round:i32,absolute:i32,max:i32,budget:i32,pending:bool,recoverable:bool}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("recovery schema mismatch")};
            let d=corelib::recovery_state::after_pass(i.round,i.absolute,i.max,i.budget,i.pending,i.recoverable);
            let action=match d.action {corelib::recovery_state::Action::Stop=>"stop",corelib::recovery_state::Action::Prompt=>"prompt",corelib::recovery_state::Action::Delayed=>"delayed"};
            serde_json::json!({"ok":{"version":1,"action":action,"round":d.round,"pending":d.pending,"inFlight":d.in_flight}}).to_string()
        }
        "attention_full_prepare_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,bits:String,quality:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {average:i32,day:i32,logs:Vec<Log>}
            let input:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("attention prepare schema mismatch")};
            let mut logs=Vec::new();for l in input.logs {if l.bits.len()!=8 || !l.bits.bytes().all(|b|b.is_ascii_hexdigit()){return err("bad sleep bits")};let bits=match u32::from_str_radix(&l.bits,16){Ok(b)=>b,Err(_)=>return err("bad sleep bits")};logs.push(corelib::attention_full::SleepLog{date:l.date,hours:f32::from_bits(bits),quality:l.quality});}
            match corelib::attention_full::prepare(input.average,input.day,&logs){Some((until,avg))=>serde_json::json!({"ok":{"version":1,"daysUntilPeriod":until,"sleepBits":avg.map(|a|format!("{:016x}",a.to_bits()))}}).to_string(),None=>err("unsupported sleep value")}
        }
        "attention_full_render_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {branch:String,title:String,message:String,until:i32,day:i32,sleep:String}
            let i:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("attention render schema mismatch")};
            match corelib::attention_full::render(&i.branch,&i.title,&i.message,i.until,i.day,&i.sleep) {
                Some(x)=>serde_json::json!({"ok":{"version":1,"item":{"id":x.id,"category":x.category,"headline":x.headline,"message":x.message,"whyThisAppeared":x.why_this_appeared,"careSuggestion":x.care_suggestion,"isUrgent":x.is_urgent}}}).to_string(),None=>err("unknown nonsafety output branch")
            }
        }
        "deviation_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Symptom {key:String,label:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,pain:i32,symptoms:Vec<Symptom>}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {today:String,logs:Vec<Log>}
            let input:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("deviation full schema mismatch")};
            let logs:Vec<_>=input.logs.into_iter().map(|l|corelib::deviation_full::Log{date:l.date,pain:l.pain,symptoms:l.symptoms.into_iter().map(|s|corelib::deviation_full::Symptom{key:s.key,label:s.label}).collect()}).collect();
            match corelib::deviation_full::detect(&logs,&input.today) {
                Some(entries)=>{let values:Vec<_>=entries.into_iter().map(|d|serde_json::json!({"factorKey":d.key,"title":d.title,"baselineBits":format!("{:016x}",d.baseline.to_bits()),"recentBits":format!("{:016x}",d.recent.to_bits()),"deltaBits":format!("{:016x}",d.delta.to_bits()),"severity":d.severity,"message":d.message})).collect();serde_json::json!({"ok":{"version":1,"entries":values}}).to_string()},
                None=>err("invalid deviation today"),
            }
        }
        "report_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Tag {key:String,label:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,symptoms:Vec<Tag>,flow:String,flow_upper:Tag,pain:i32,note:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {today:String,average:i32,duration:i32,anchor:String,anchor_blank:bool,approximate:bool,logs:Vec<Log>}
            let input:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("report full schema mismatch")};
            let tag=|t:Tag|corelib::report_full::Tag{key:t.key,label:t.label};
            let logs:Vec<_>=input.logs.into_iter().map(|l|corelib::report_full::Log{date:l.date,symptoms:l.symptoms.into_iter().map(tag).collect(),flow:l.flow,flow_upper:tag(l.flow_upper),pain:l.pain,note:l.note}).collect();
            let settings=corelib::report_full::Settings{average_cycle_length:input.average,period_duration:input.duration,last_period_start_date:input.anchor,anchor_blank:input.anchor_blank,approximate:input.approximate};
            serde_json::json!({"ok":{"version":1,"text":corelib::report_full::generate_clinician_report_text(&settings,&logs,&input.today)}}).to_string()
        }
        "explain_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Prediction {phase:String,day:i32}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {prediction:Option<Prediction>}
            if v.as_object().map(|m|m.len()==1 && m.contains_key("prediction"))!=Some(true) {return err("explain full schema mismatch")}
            let input:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("explain full schema mismatch")};
            let prediction=match input.prediction {None=>None,Some(p)=>{
                let phase=match p.phase.as_str() {"MENSTRUAL"=>corelib::cycle_math::CyclePhase::Menstrual,"FOLLICULAR"=>corelib::cycle_math::CyclePhase::Follicular,"OVULATION"=>corelib::cycle_math::CyclePhase::Ovulation,"LUTEAL"=>corelib::cycle_math::CyclePhase::Luteal,_=>return err("bad explain phase")};
                Some(corelib::explain_full::Prediction{phase,day_of_cycle:p.day})
            }};
            let x=corelib::explain_full::synthesize_explain_my_body(prediction.as_ref());
            serde_json::json!({"ok":{"version":1,"result":{"summary":x.summary,"whatMightBeHappening":x.what_might_be_happening,"whatSupportsThat":x.what_supports_that,"whatElseCouldExplain":x.what_else_could_explain,"whatYouCanDo":x.what_you_can_do,"whatToWatch":x.what_to_watch,"whenToGetChecked":x.when_to_get_checked,"safetyLevel":"LOW"}}}).to_string()
        }
        "timeline_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Tag {key:String,label:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,month_title:String,energy:Tag,bleeding:bool,symptoms:Vec<Tag>}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {logs:Vec<Log>}
            let input:Input=match serde_json::from_value(v){Ok(i)=>i,Err(_)=>return err("timeline full schema mismatch")};
            let tag=|t:Tag|corelib::timeline_full::Tag{key:t.key,label:t.label};
            let logs:Vec<_>=input.logs.into_iter().map(|l|corelib::timeline_full::Log{date:l.date,month_title:l.month_title,energy:tag(l.energy),bleeding:l.bleeding,symptoms:l.symptoms.into_iter().map(tag).collect()}).collect();
            let entries:Vec<_>=corelib::timeline_full::generate(&logs).into_iter().map(|e|serde_json::json!({"monthTitle":e.month_title,"cycleLengthDays":e.cycle_length_days,"periodBleedingDays":e.period_bleeding_days,"dominantEnergy":e.dominant_energy,"dominantSymptom":e.dominant_symptom,"patternNoticed":e.pattern_noticed})).collect();
            serde_json::json!({"ok":{"version":1,"entries":entries}}).to_string()
        }
        "pattern_full_plan_v1" | "pattern_full_v1" => {
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Symptom {key:String,label:String,lower:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Log {date:String,symptoms:Vec<Symptom>}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Weight {day:i64,bits:String}
            #[derive(serde::Deserialize)] #[serde(deny_unknown_fields)]
            struct Input {today:String,anchor:String,average:i32,duration:i32,luteal:i32,logs:Vec<Log>,weights:Vec<Weight>}
            let i:Input=match serde_json::from_value(v) {Ok(i)=>i,Err(_)=>return err("pattern full schema mismatch")};
            let logs:Vec<_>=i.logs.into_iter().map(|l|corelib::pattern_full::PatternFullLog{date_string:l.date,symptoms:l.symptoms.into_iter().map(|s|corelib::pattern_full::PatternSymptom{key:s.key,label:s.label,lower:s.lower}).collect()}).collect();
            if contract=="pattern_full_plan_v1" {
                if !i.weights.is_empty(){return err("plan weights must be empty")}
                return match corelib::pattern_full::weight_offsets(&logs,&i.today) {Some(days)=>serde_json::json!({"ok":{"version":1,"days":days}}).to_string(),None=>err("invalid pattern today")};
            }
            let mut weights=Vec::new();
            for w in i.weights {if w.bits.len()!=16 || !w.bits.bytes().all(|b|b.is_ascii_hexdigit()) {return err("bad pattern weight bits")}
                let bits=match u64::from_str_radix(&w.bits,16){Ok(b)=>b,Err(_)=>return err("bad pattern weight bits")};weights.push((w.day,f64::from_bits(bits)));}
            let settings=corelib::pattern_engine::PatternSettings{average_cycle_length:i.average,period_duration:i.duration,luteal_phase_days:i.luteal,last_period_start_date:i.anchor};
            match corelib::pattern_full::detect_patterns(&settings,&logs,&i.today,&weights) {
                Some(patterns)=> {let values:Vec<_>=patterns.into_iter().map(|p|serde_json::json!({"id":p.id,"title":p.title,"supportingObservations":p.supporting_observations,"confidence":p.confidence,"cyclePhase":p.cycle_phase,"explanation":p.explanation,"actionableCare":p.actionable_care})).collect();serde_json::json!({"ok":{"version":1,"patterns":values}}).to_string()},
                None=>err("pattern weight coverage mismatch"),
            }
        }
        "learned_full_v1" => {
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Symptom {key:String,label:String}
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Log {date:String,symptoms:Vec<Symptom>}
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Input {completed:bool,average:i32,duration:i32,luteal:i32,anchor:String,setup:Vec<Symptom>,logs:Vec<Log>}
            let input:Input=match serde_json::from_value(v) {Ok(i)=>i,Err(_)=>return err("learned full schema mismatch")};
            let sym=|s:Symptom| corelib::learned_full::Symptom{key:s.key,label:s.label};
            let logs:Vec<_>=input.logs.into_iter().map(|l|corelib::learned_full::LearningLog{date_string:l.date,symptoms:l.symptoms.into_iter().map(sym).collect()}).collect();
            let profile=corelib::learned_full::LearningProfile{is_completed:false,symptoms:input.setup.into_iter().map(sym).collect()};
            let settings=corelib::learning::LearningSettings{is_onboarding_completed:input.completed,average_cycle_length:input.average,period_duration:input.duration,luteal_phase_days:input.luteal,last_period_start_date:input.anchor};
            let observations=corelib::learned_full::compile_learned_observations(&settings,&logs,Some(&profile));
            let values:Vec<_>=observations.into_iter().map(|o|serde_json::json!({"id":o.id,"category":o.category,"headline":o.headline,"description":o.description,"confidence":o.confidence})).collect();
            serde_json::json!({"ok":{"version":1,"observations":values}}).to_string()
        }
        "phase_full_v1" => {
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Input { date:String, anchor:String, average:i32, duration:i32, luteal:i32 }
            let input:Input=match serde_json::from_value(v) {
                Ok(input)=>input,Err(_)=>return err("phase full schema mismatch"),
            };
            match corelib::phase_full::calculate(&input.date,&input.anchor,input.average,input.duration,input.luteal) {
                Some(phase)=>serde_json::json!({"ok":{"version":1,"phase":phase.kotlin_name()}}).to_string(),
                None=>err("phase date is not a Java LocalDate"),
            }
        }
        "baseline_full_v1" => {
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Log { date: String, bleeding: bool }
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Input { average: i32, duration: i32, logs: Vec<Log> }
            let input: Input = match serde_json::from_value(v) {
                Ok(input) => input,
                Err(_) => return err("baseline full schema mismatch"),
            };
            let logs: Vec<_> = input.logs.into_iter().map(|l| corelib::baseline_full::Log {
                date:l.date, bleeding:l.bleeding,
            }).collect();
            let m=corelib::baseline_full::compute(input.average,input.duration,&logs);
            serde_json::json!({"ok":{"version":1,"metrics":{
                "averageCycleLength":m.average_cycle_length,"medianCycleLength":m.median_cycle_length,
                "minCycleLength":m.min_cycle_length,"maxCycleLength":m.max_cycle_length,
                "variabilityDays":m.variability_days,"isRegular":m.is_regular,
                "totalLoggedDays":m.total_logged_days,"observedCycleIntervals":m.observed_cycle_intervals
            }}}).to_string()
        }
        "doctor_summary_v1" => {
            // Strict schema, no absent/wrong-type defaults or numeric truncation.
            // Input text labels are the exact JVM platform primitive, not new copy.
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Symptom { key: String, label: String }
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Log { date: String, symptoms: Vec<Symptom>, pain: i32, flow: String }
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Input { logs: Vec<Log> }
            let input: Input = match serde_json::from_value(v) {
                Ok(input) => input,
                Err(_) => return err("doctor summary schema mismatch"),
            };
            let logs: Vec<_> = input.logs.into_iter().map(|log| corelib::doctor_summary::Log {
                date: log.date, pain: log.pain, flow: log.flow,
                symptoms: log.symptoms.into_iter().map(|s| corelib::doctor_summary::Symptom {
                    key: s.key, label: s.label,
                }).collect(),
            }).collect();
            serde_json::json!({"ok": {"version": 1,
                "questions": corelib::doctor_summary::generate(&logs)}}).to_string()
        }
        "sync_profile_on_settings" => {
            // Strict contract: hasProfile boolean; existingOption string when
            // hasProfile, string-or-null otherwise; both averages integers in
            // i32 (Kotlin sends stored Ints; anything else is a contract bug).
            use corelib::settings_core::sync_profile_on_settings;
            let has_profile = match v["hasProfile"].as_bool() {
                Some(b) => b,
                None => return err("hasProfile missing or not a boolean".into()),
            };
            let existing = if has_profile {
                match v["existingOption"].as_str() {
                    Some(s) => Some(s.to_string()),
                    None => return err("existingOption missing or not a string while hasProfile".into()),
                }
            } else {
                if !(v["existingOption"].is_string() || v["existingOption"].is_null()) {
                    return err("existingOption must be string or null".into());
                }
                None
            };
            let req_i32 = |k: &str| -> Result<i32, String> {
                match v[k].as_i64() {
                    Some(n) => i32::try_from(n).map_err(|_| format!("{k} out of i32 range")),
                    None => Err(format!("{k} missing or not an integer")),
                }
            };
            let (new_avg, prev_avg) = match (req_i32("newAverage"), req_i32("previousAverage")) {
                (Ok(a), Ok(b)) => (a, b),
                (Err(e), _) | (_, Err(e)) => return err(&e),
            };
            match sync_profile_on_settings(existing.as_deref(), new_avg, prev_avg) {
                Some(opt) => serde_json::json!({"ok": {"writeProfile": true, "cycleLengthOption": opt}}).to_string(),
                None => serde_json::json!({"ok": {"writeProfile": false, "cycleLengthOption": null}}).to_string(),
            }
        }
        "sanitize_coin_amount" => {
            let raw = v["raw"].as_str().unwrap_or("");
            let (text, rejection) = sanitize_coin_amount_input(raw);
            serde_json::json!({"ok": {"text": text, "rejection": rejection}}).to_string()
        }
        "validate_period_dates" => {
            // ISO strings or null; parse is probe-locked (cycle_math).
            // Full domain: java.time.LocalDate admits years in
            // +/-999999999 beyond chrono's range, so valid-but-unrepresentable
            // dates are compared by epoch day - java.time's own compareTo
            // unit; epoch millis would overflow i64 at these extremes -
            // instead of reporting false; "supported": true marks an
            // authoritative reply (the bridge requires that marker and
            // falls back to Kotlin on any missing/malformed/unsupported one).
            let p = |s: Option<&str>| s.map(corelib::cycle_math::parse_local_date_iso_full);
            let result = match (p(v["start"].as_str()), p(v["end"].as_str())) {
                (Some(a), Some(b)) => corelib::cycle_math::validate_period_dates_full(a, b),
                _ => None,
            };
            serde_json::json!({"ok": result.unwrap_or(false), "supported": true}).to_string()
        }
        "calculate_day_prediction" => {
            // Full day-prediction route (core cycle_math port of
            // CycleRepository.calculateDayPrediction, main@31b88f5). Strict
            // settings contract: CycleSettings carries non-null Int fields,
            // so a missing, wrong-typed, or out-of-Int32 numeric is a
            // contract breach (fallback), never a silent 28/5/14 substitute
            // or an i64->i32 truncation. lastPeriodStartDate must be a real
            // JSON string.
            let s = &v["settings"];
            let int_field = |k: &str| {
                s.get(k)
                    .and_then(|x| x.as_i64())
                    .and_then(|x| i32::try_from(x).ok())
            };
            let anchor = s.get("lastPeriodStartDate").and_then(|x| x.as_str());
            let date = v["date"]
                .as_str()
                .and_then(corelib::cycle_math::parse_local_date_iso);
            if !s.is_object() {
                serde_json::json!({"error": "settings object required"}).to_string()
            } else if let (Some(acl), Some(pd), Some(lpd), Some(anchor)) = (
                int_field("averageCycleLength"),
                int_field("periodDuration"),
                int_field("lutealPhaseDays"),
                anchor,
            ) {
                match date {
                    None => serde_json::json!({"error": "invalid date"}).to_string(),
                    Some(d) => {
                        use corelib::cycle_math::ParsedLocalDate;
                        match corelib::cycle_math::parse_local_date_iso_full(anchor) {
                            // Java-valid but outside chrono: the Kotlin route
                            // COMPUTES an answer here; the native port must
                            // fall back, not claim ok:null.
                            ParsedLocalDate::Unrepresentable { .. } => {
                                serde_json::json!({"error": "anchor outside native range"})
                                    .to_string()
                            }
                            // Java-invalid (incl. blank and "-0000", which Java
                            // rejects): the Kotlin route's own null.
                            ParsedLocalDate::Invalid => {
                                serde_json::json!({"ok": serde_json::Value::Null}).to_string()
                            }
                            ParsedLocalDate::Representable(last_start) => {
                                // Kotlin does DAYS.between(...).toInt() BEFORE
                                // floorMod; beyond Int-day spans it produces a
                                // wrapped answer the native port declines to
                                // reproduce (fallback, not new policy).
                                // Unreachable while both dates are chrono-
                                // representable (max span ~524k YEARS ~ 191M
                                // days < Int32.MAX), kept as the explicit
                                // domain edge.
                                if i32::try_from((d - last_start).num_days()).is_err() {
                                    serde_json::json!({"error": "span outside Int range"})
                                        .to_string()
                                } else {
                                    match corelib::cycle_math::calculate_day_prediction(
                                        d, anchor, acl, pd, lpd,
                                    ) {
                                        None => serde_json::json!({"ok": serde_json::Value::Null})
                                            .to_string(),
                                        Some(pred) => serde_json::json!({"ok": {
                                            "dayOfCycle": pred.day_of_cycle,
                                            "phase": pred.phase.kotlin_name(),
                                            "isPeriod": pred.is_period,
                                            "isFertile": pred.is_fertile,
                                            "isOvulation": pred.is_ovulation,
                                            "fertilityConfidence": pred.fertility_confidence,
                                            "pregnancyChance": pred.pregnancy_chance,
                                        }})
                                        .to_string(),
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                serde_json::json!({"error": "settings contract"}).to_string()
            }
        }
        "flow_anchor_decision" => {
            // Tracking-write anchor route (port of the flow-anchor policy in
            // CycleRepository.saveDayLogBound). Exact Kotlin semantics: a
            // saved log advances settings.lastPeriodStartDate only when its
            // flowLevel is exactly one of LIGHT/MODERATE/HEAVY/
            // UNUSUALLY_HEAVY (case-sensitive membership, no trim) AND the
            // prior anchor is blank/unparseable OR the log date is strictly
            // after prior+15 days (LocalDate.isAfter: +15 itself is false).
            // The new anchor is the log's dateString echoed verbatim (Kotlin
            // writes the input string, never a reformat). CycleDayLog fields
            // are non-null Strings, so a missing/wrong-typed flowLevel or
            // date is a contract breach (fallback), never a silent "".
            // Kotlin throws on a malformed log date inside its write
            // transaction; the arm errors so the bridge falls back to that
            // same behavior. Java-valid dates outside chrono's range (log
            // date or prior anchor) are declined as fallback, like the M6
            // anchor rule, never reproduced.
            let flow = v["flowLevel"].as_str();
            let date_str = v["date"].as_str();
            let prior = v["priorAnchor"].as_str();
            match (flow, date_str) {
                (Some(flow), Some(date_str)) => {
                    const ANCHOR_FLOWS: [&str; 4] =
                        ["LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY"];
                    if !ANCHOR_FLOWS.contains(&flow) {
                        serde_json::json!({"ok": {"update": false}}).to_string()
                    } else {
                        use chrono::Datelike;
                        use corelib::cycle_math::ParsedLocalDate;
                        match corelib::cycle_math::parse_local_date_iso_full(date_str) {
                            ParsedLocalDate::Unrepresentable { .. } => {
                                serde_json::json!({"error": "log date outside native range"})
                                    .to_string()
                            }
                            ParsedLocalDate::Invalid => {
                                serde_json::json!({"error": "invalid log date"}).to_string()
                            }
                            ParsedLocalDate::Representable(d) => {
                                let start = match prior {
                                    None => None,
                                    Some(p) => match corelib::cycle_math::parse_local_date_iso_full(p) {
                                        ParsedLocalDate::Unrepresentable { .. } => {
                                            return err("prior anchor outside native range")
                                        }
                                        ParsedLocalDate::Invalid => None,
                                        ParsedLocalDate::Representable(s) => Some(s),
                                    },
                                };
                                // isAfter(start.plusDays(15)) as i64 epoch-day
                                // comparison: no chrono overflow possible and
                                // identical to the Java comparison at every
                                // representable date.
                                let update = match start {
                                    None => true,
                                    Some(s) => {
                                        d.num_days_from_ce() as i64
                                            > s.num_days_from_ce() as i64 + 15
                                    }
                                };
                                if update {
                                    serde_json::json!({"ok": {"update": true, "newAnchor": date_str}})
                                        .to_string()
                                } else {
                                    serde_json::json!({"ok": {"update": false}}).to_string()
                                }
                            }
                        }
                    }
                }
                _ => serde_json::json!({"error": "flowLevel and date strings required"})
                    .to_string(),
            }
        }
        "is_complete_pin" => {
            let pin = v["pin"].as_str().unwrap_or("");
            serde_json::json!({"ok": is_complete_pin(pin)}).to_string()
        }
        "verify_pin" => {
            // stored may be JSON null (no stored PIN); candidate/device_id default "".
            let stored = v["stored"].as_str();
            let candidate = v["candidate"].as_str().unwrap_or("");
            let device_id = v["device_id"].as_str().unwrap_or("");
            let r = verify_pin(stored, candidate, device_id);
            let label = match r {
                PinVerify::Current => "current",
                PinVerify::LegacyMigrate => "legacy_migrate",
                PinVerify::Rejected => "rejected",
            };
            serde_json::json!({"ok": label}).to_string()
        }
        "encode_pin" => {
            // Salt supplied by the caller (Kotlin SecureRandom) as 16-byte hex so
            // the FFI stays deterministic; malformed salt is a hard error.
            let pin = v["pin"].as_str().unwrap_or("");
            let salt_hex = v["salt_hex"].as_str().unwrap_or("");
            // ASCII check first: slicing a str at non-char boundaries panics.
            if !salt_hex.is_ascii() || salt_hex.len() != 32 {
                return err("encode_pin: salt_hex must be exactly 16 bytes hex");
            }
            let mut salt = [0u8; 16];
            for i in 0..16 {
                match u8::from_str_radix(&salt_hex[i*2..i*2+2], 16) {
                    Ok(b) => salt[i] = b,
                    Err(_) => return err("encode_pin: salt_hex must be exactly 16 bytes hex"),
                }
            }
            serde_json::json!({"ok": encode_pin_pbkdf2_with_salt(pin, &salt)}).to_string()
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
            let g = corelib::lifecycle::startup_gate(
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
            serde_json::json!({"ok": corelib::lifecycle::effective_user_id(uid, device)}).to_string()
        }
        "ensure_setup_ok" => {
            let body = v["body"].as_str().unwrap_or("");
            serde_json::json!({"ok": corelib::verified_profile::ensure_setup_ok(body)}).to_string()
        }
        "has_remote_profile" => {
            let r = corelib::reconcile::has_remote_profile_response(
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
            match corelib::verified_profile::parse_verified_profile(
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
            let o = corelib::reconcile::reconcile_offline_claim_response(
                v["httpOk"].as_bool().unwrap_or(false),
                v["body"].as_str().unwrap_or(""),
                &bd("balance"), &bd("earned"),
                v["rank"].as_str().unwrap_or("LUMEN"),
                v["pendingDate"].as_str().unwrap_or(""),
            );
            use corelib::reconcile::ReconcileOutcome as R;
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
        "parse_log_entry" => {
            let text = v["text"].as_str().unwrap_or("");
            let r = corelib::log_parser::parse(text);
            let items: Vec<serde_json::Value> = r.items.iter().map(|i| serde_json::json!({
                "category": i.category, "label": i.label, "detail": i.detail,
                "iconEmoji": i.icon_emoji})).collect();
            serde_json::json!({"ok": {
                "rawText": r.raw_text,
                "items": items,
                "followUpQuestion": r.follow_up_question,
                "flowLevelResult": r.flow_level_result,
                "energyResult": r.energy_result,
                "moodResult": r.mood_result,
                "symptomsResult": r.symptoms_result,
                "painIntensityResult": r.pain_intensity_result}}).to_string()
        }
        "decide_reconnect" => reconnect(v, false),
        "decide_reconnect_trailing" => reconnect(v, true),
        "evaluate_safety" => (|| {
            // CycleDayLog fields are non-null on the Kotlin side (defaults
            // ""/0), so a present-but-wrong-typed field is a contract breach
            // (fallback), never a silent default. "log" itself is either a
            // real object or JSON null (the Kotlin evaluate(null) route).
            let log = match &v["log"] {
                serde_json::Value::Null => None,
                serde_json::Value::Object(o) => {
                    let sf = |k: &str| o.get(k).and_then(|x| x.as_str());
                    let pi = o
                        .get("painIntensity")
                        .and_then(|x| x.as_i64())
                        .and_then(|x| i32::try_from(x).ok());
                    match (sf("flowLevel"), sf("symptomsCsv"), sf("vaginalHealthCsv"), sf("urinaryHealthCsv"), pi) {
                        (Some(fl), Some(sc), Some(vc), Some(uc), Some(p)) => Some(SafetyLog {
                            flow_level: fl.into(),
                            symptoms_csv: sc.into(),
                            vaginal_health_csv: vc.into(),
                            urinary_health_csv: uc.into(),
                            pain_intensity: p,
                        }),
                        _ => {
                            return serde_json::json!({"error": "log field contract"}).to_string()
                        }
                    }
                }
                _ => return serde_json::json!({"error": "log must be object or null"}).to_string(),
            };
            let ev = safety::evaluate(log.as_ref());
            // Kotlin enum NAMES (SEEK_PROFESSIONAL_ADVICE), not Debug labels.
            serde_json::json!({"ok": {"level": ev.level.name(), "title": ev.title,
                "message": ev.message, "actionAdvice": ev.action_advice, "isRedFlag": ev.is_red_flag}})
                .to_string()
        })(),
        "attention_nonsafety_priority" => (|| {
            // Bounded two-pass NON-SAFETY priority selection. Selection only:
            // evidence preparation, all dates and all copy stay Kotlin-side.
            use corelib::attention::{select_nonsafety_priority, NonsafetyDecision, NonsafetyEvidence};
            use corelib::something_changed::DeviationSeverity;
            let b = |k: &str| v.get(k).and_then(|x| x.as_bool());
            let i32f = |k: &str| v.get(k).and_then(|x| x.as_i64()).and_then(|n| i32::try_from(n).ok());
            let safety = match b("safetyUrgentOrSeek") { Some(x) => x, None => return err("safetyUrgentOrSeek missing or not a boolean") };
            let sevs_v = match v.get("deviationSeverities").and_then(|x| x.as_array()) {
                Some(a) => a,
                None => return err("deviationSeverities missing or not an array"),
            };
            let mut sevs: Vec<DeviationSeverity> = Vec::with_capacity(sevs_v.len());
            for s in sevs_v {
                match s.as_str() {
                    Some("STRONG") => sevs.push(DeviationSeverity::Strong),
                    Some("SIGNIFICANT") => sevs.push(DeviationSeverity::Significant),
                    Some("NOTABLE") => sevs.push(DeviationSeverity::Notable),
                    _ => return err("deviationSeverities entry not a known severity name"),
                }
            }
            let has_prediction = match b("hasPrediction") { Some(x) => x, None => return err("hasPrediction missing or not a boolean") };
            let day_of_cycle = match i32f("dayOfCycle") { Some(x) => x, None => return err("dayOfCycle missing or not an i32") };
            let is_period = match b("isPeriod") { Some(x) => x, None => return err("isPeriod missing or not a boolean") };
            let is_fertile = match b("isFertile") { Some(x) => x, None => return err("isFertile missing or not a boolean") };
            let days_until = match i32f("daysUntilPeriod") { Some(x) => x, None => return err("daysUntilPeriod missing or not an i32") };
            let sleep_avg = match v.get("sleepAverage") {
                None => return err("sleepAverage missing"),
                Some(serde_json::Value::Null) => None,
                Some(n) => match n.as_f64() {
                    Some(f) if f.is_finite() => Some(f),
                    _ => return err("sleepAverage not a finite number"),
                },
            };
            let checkin_resolved = match b("checkinResolved") { Some(x) => x, None => return err("checkinResolved missing or not a boolean") };
            let checkin = if !checkin_resolved {
                None
            } else {
                match v.get("daysSinceLastLog") {
                    None => return err("daysSinceLastLog missing"),
                    Some(serde_json::Value::Null) => Some(None),
                    Some(n) => match n.as_i64() {
                        Some(d) => Some(Some(d)),
                        None => return err("daysSinceLastLog not an integer"),
                    },
                }
            };
            let ev = NonsafetyEvidence {
                safety_urgent_or_seek: safety,
                deviation_severities: &sevs,
                has_prediction,
                day_of_cycle,
                is_period,
                is_fertile,
                days_until_period: days_until,
                sleep_average: sleep_avg,
                checkin_days_since_last_log: checkin,
            };
            let ok = match select_nonsafety_priority(&ev) {
                NonsafetyDecision::SafetyPriority => serde_json::json!({"branch": "safety_priority"}),
                NonsafetyDecision::BaselineShiftStrong { deviation_index } => serde_json::json!({"branch": "baseline_shift_strong", "deviationIndex": deviation_index}),
                NonsafetyDecision::BaselineShiftNotable { deviation_index } => serde_json::json!({"branch": "baseline_shift_notable", "deviationIndex": deviation_index}),
                NonsafetyDecision::None => serde_json::json!({"branch": "none"}),
                NonsafetyDecision::PeriodSleepTaper => serde_json::json!({"branch": "period_sleep_taper"}),
                NonsafetyDecision::FertileWindowNotice => serde_json::json!({"branch": "fertile_window_notice"}),
                NonsafetyDecision::PhaseMenstrualStart => serde_json::json!({"branch": "phase_menstrual_start"}),
                NonsafetyDecision::NeedsCheckin => serde_json::json!({"branch": "needs_checkin"}),
                NonsafetyDecision::WellbeingCheckin => serde_json::json!({"branch": "wellbeing_checkin"}),
            };
            serde_json::json!({"ok": ok}).to_string()
        })(),
        "community_transport_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port community_transport (CommunityGuidesService.kt +
            // CommunityPulsePinger, main@31b88f5 lineage; TransportProbe
            // JVM corpus). Request SHAPES and verdicts only: no I/O, no
            // credentials cross (the bearer decision returns which EXISTING
            // caller-held value to use, never a new secret). Payload content
            // is corpus-locked; org.json key order is insertion order on
            // AOSP, matching serde's emission here.
            use corelib::community_transport as ct;
            let op = match v.get("op").and_then(|x| x.as_str()) { Some(s) => s, None => return err("op missing or not a string") };
            let s = |k: &str| v.get(k).and_then(|x| x.as_str());
            let opt_s = |k: &str| match v.get(k) {
                None => None,
                Some(serde_json::Value::Null) => None,
                Some(x) => x.as_str(),
            };
            let bad = |m: &str| err(m);
            match op {
                "current_user_id" => {
                    let r = ct::current_user_id(opt_s("userId"));
                    match r { Some(u) => serde_json::json!({"ok":{"version":1,"value":u}}).to_string(), None => serde_json::json!({"ok":{"version":1,"value":null}}).to_string() }
                }
                "fetch_topics_path" => serde_json::json!({"ok":{"version":1,"path":ct::fetch_topics_request().path}}).to_string(),
                "fetch_messages_path" => {
                    let topic = match s("topicId") { Some(x) => x, None => return bad("topicId missing or not a string") };
                    serde_json::json!({"ok":{"version":1,"path":ct::fetch_messages_request(topic).path}}).to_string()
                }
                "ensure_membership_body" => {
                    let user = match s("userId") { Some(x) => x, None => return bad("userId missing or not a string") };
                    let topic = match s("topicId") { Some(x) => x, None => return bad("topicId missing or not a string") };
                    let r = ct::ensure_membership_request(user, topic);
                    serde_json::json!({"ok":{"version":1,"body":r.body}}).to_string()
                }
                "membership_success" => {
                    let code = match v.get("code").and_then(|x| x.as_u64()).and_then(|n| u16::try_from(n).ok()) { Some(x) => x, None => return bad("code missing or not a u16") };
                    serde_json::json!({"ok":{"version":1,"verdict":ct::membership_success(code)}}).to_string()
                }
                "post_content_allowed" => {
                    let trimmed = match s("trimmed") { Some(x) => x, None => return bad("trimmed missing or not a string") };
                    serde_json::json!({"ok":{"version":1,"verdict":ct::post_content_allowed(trimmed)}}).to_string()
                }
                "post_message_body" => {
                    let topic = match s("topicId") { Some(x) => x, None => return bad("topicId missing or not a string") };
                    let user = match s("userId") { Some(x) => x, None => return bad("userId missing or not a string") };
                    let alias = match s("senderAlias") { Some(x) => x, None => return bad("senderAlias missing or not a string") };
                    let content = match s("contentTrimmed") { Some(x) => x, None => return bad("contentTrimmed missing or not a string") };
                    let r = ct::post_message_request(topic, user, alias, content);
                    serde_json::json!({"ok":{"version":1,"body":r.body}}).to_string()
                }
                "flag_message_body" => {
                    let id = match s("remoteMessageId") { Some(x) => x, None => return bad("remoteMessageId missing or not a string") };
                    let r = ct::flag_message_request(id);
                    serde_json::json!({"ok":{"version":1,"body":r.body}}).to_string()
                }
                "flag_success" => {
                    let code = match v.get("code").and_then(|x| x.as_u64()).and_then(|n| u16::try_from(n).ok()) { Some(x) => x, None => return bad("code missing or not a u16") };
                    let body = match s("body") { Some(x) => x, None => return bad("body missing or not a string") };
                    serde_json::json!({"ok":{"version":1,"verdict":ct::flag_success(code, body)}}).to_string()
                }
                "pulse_bearer" => {
                    let anon = match s("anonKey") { Some(x) => x, None => return bad("anonKey missing or not a string") };
                    serde_json::json!({"ok":{"version":1,"value":ct::pulse_bearer(opt_s("accessToken"), anon)}}).to_string()
                }
                "pulse_path" => serde_json::json!({"ok":{"version":1,"path":ct::pulse_request().path}}).to_string(),
                _ => err("unknown community_transport op"),
            }
        })(),
        "session_token_expiry_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port session_token::token_expiry
            // (SupabaseSessionManager.kt tokenExpiry, main@31b88f5 lineage;
            // SessionTokenParity JVM locks t01-t08). 0 mirrors the source's
            // catch-all; authenticity stays the server's job.
            let token = match v.get("token").and_then(|x| x.as_str()) { Some(s) => s, None => return err("token missing or not a string") };
            serde_json::json!({"ok":{"version":1,"expiry":corelib::session_token::token_expiry(token)}}).to_string()
        })(),
        "session_token_valid_for_owner_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port session_token::valid_token_for_owner
            // (SupabaseSessionManager.kt validTokenForOwner; structural
            // consistency only). nowSecs is caller-supplied so the Kotlin
            // fallback and native path evaluate the same instant.
            let token = match v.get("token").and_then(|x| x.as_str()) { Some(s) => s, None => return err("token missing or not a string") };
            let owner = match v.get("owner").and_then(|x| x.as_str()) { Some(s) => s, None => return err("owner missing or not a string") };
            let now_secs = match v.get("nowSecs").and_then(|x| x.as_i64()) { Some(n) => n, None => return err("nowSecs missing or not an integer") };
            serde_json::json!({"ok":{"version":1,"valid":corelib::session_token::valid_token_for_owner(token, owner, now_secs)}}).to_string()
        })(),
        "auth_sign_in_response_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the NON-CLOCK
            // sign-in response interpretation (auth_link::
            // interpret_sign_in_response, shared with the auth_flows port -
            // ONE implementation). Status/error mapping (auth_errors
            // corpus), parse, confirmation check, user read, RAW
            // session-field extraction. clearSession/session WRITES and the
            // expiry clock read stay at the Kotlin caller's original commit
            // points. The PASSWORD never crosses: it lives only in the
            // request body, which stays Kotlin-built. The response body
            // (which carries tokens on success) crosses IN; session fields
            // cross OUT - same no-logging/no-persistence/no-egress
            // accounting as the session_token ops.
            let successful = match v.get("successful").and_then(|x| x.as_bool()) { Some(b) => b, None => return err("successful missing or not a bool") };
            let body = match v.get("body").and_then(|x| x.as_str()) { Some(s) => s, None => return err("body missing or not a string") };
            let email = match v.get("normalizedEmail").and_then(|x| x.as_str()) { Some(s) => s, None => return err("normalizedEmail missing or not a string") };
            match corelib::auth_link::interpret_sign_in_response(successful, body, email) {
                corelib::auth_link::SignInInterpretation::Error(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error","message":m}}).to_string(),
                corelib::auth_link::SignInInterpretation::VerificationRequired(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"verify","message":m}}).to_string(),
                corelib::auth_link::SignInInterpretation::Success { user, message, session } =>
                    serde_json::json!({"ok":{"version":1,"verdict":"success","message":message,
                        "user":{"id":user.id,"email":user.email,"emailConfirmedAt":user.email_confirmed_at,"anonymous":user.is_anonymous},
                        "session":{"mode":session.mode,"userId":session.user_id,"userEmail":session.user_email,
                            "accessToken":session.access_token,"refreshToken":session.refresh_token,
                            "expiresAt":session.expires_at,"expiresIn":session.expires_in}}}).to_string(),
            }
        })(),
        "auth_sign_up_response_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the NON-CLOCK
            // sign-up response interpretation (auth_link::
            // interpret_sign_up_response, shared with the auth_flows port).
            // The PASSWORD never crosses (the request stays Kotlin-built);
            // all commits and the expiry clock read stay at the caller.
            let successful = match v.get("successful").and_then(|x| x.as_bool()) { Some(b) => b, None => return err("successful missing or not a bool") };
            let body = match v.get("body").and_then(|x| x.as_str()) { Some(s) => s, None => return err("body missing or not a string") };
            let email = match v.get("normalizedEmail").and_then(|x| x.as_str()) { Some(s) => s, None => return err("normalizedEmail missing or not a string") };
            match corelib::auth_link::interpret_sign_up_response(successful, body, email) {
                corelib::auth_link::SignUpInterpretation::ErrorClearPending(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error_clear_pending","message":m}}).to_string(),
                corelib::auth_link::SignUpInterpretation::ErrorPlain(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error","message":m}}).to_string(),
                corelib::auth_link::SignUpInterpretation::VerificationRequired(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"verify","message":m}}).to_string(),
                corelib::auth_link::SignUpInterpretation::Success { user, message, session } =>
                    serde_json::json!({"ok":{"version":1,"verdict":"success","message":message,
                        "user":{"id":user.id,"email":user.email,"emailConfirmedAt":user.email_confirmed_at,"anonymous":user.is_anonymous},
                        "session":{"mode":session.mode,"userId":session.user_id,"userEmail":session.user_email,
                            "accessToken":session.access_token,"refreshToken":session.refresh_token,
                            "expiresAt":session.expires_at,"expiresIn":session.expires_in}}}).to_string(),
            }
        })(),
        "auth_guest_response_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): NON-CLOCK continueAsGuest
            // response interpretation (auth_link::interpret_guest_response).
            let successful = match v.get("successful").and_then(|x| x.as_bool()) { Some(b) => b, None => return err("successful missing or not a bool") };
            let body = match v.get("body").and_then(|x| x.as_str()) { Some(s) => s, None => return err("body missing or not a string") };
            match corelib::auth_link::interpret_guest_response(successful, body) {
                corelib::auth_link::GuestInterpretation::Error(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error","message":m}}).to_string(),
                corelib::auth_link::GuestInterpretation::Success { user_id, message, session } =>
                    serde_json::json!({"ok":{"version":1,"verdict":"success","message":message,"userId":user_id,
                        "session":{"mode":session.mode,"userId":session.user_id,"userEmail":session.user_email,
                            "accessToken":session.access_token,"refreshToken":session.refresh_token,
                            "expiresAt":session.expires_at,"expiresIn":session.expires_in}}}).to_string(),
            }
        })(),
        "auth_set_password_response_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): NON-CLOCK setPassword
            // response interpretation (auth_link::
            // interpret_set_password_response). The PASSWORD never crosses:
            // it lives only in the Kotlin-built PUT body.
            let successful = match v.get("successful").and_then(|x| x.as_bool()) { Some(b) => b, None => return err("successful missing or not a bool") };
            let body = match v.get("body").and_then(|x| x.as_str()) { Some(s) => s, None => return err("body missing or not a string") };
            let fallback_email = match v.get("fallbackEmail").and_then(|x| x.as_str()) { Some(s) => s, None => return err("fallbackEmail missing or not a string") };
            match corelib::auth_link::interpret_set_password_response(successful, body, fallback_email) {
                corelib::auth_link::SetPasswordInterpretation::Error(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error","message":m}}).to_string(),
                corelib::auth_link::SetPasswordInterpretation::Success { user, message } =>
                    serde_json::json!({"ok":{"version":1,"verdict":"success","message":message,
                        "user":{"id":user.id,"email":user.email,"emailConfirmedAt":user.email_confirmed_at,"anonymous":user.is_anonymous}}}).to_string(),
            }
        })(),
        "auth_flow_status_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): the status split + corpus
            // error mapping for sendMagicLink / sendPasswordReset /
            // linkGuestAccount (auth_link::interpret_flow_status). Every
            // commit and success message stays with the caller.
            let operation = match v.get("operation").and_then(|x| x.as_str()) {
                Some("MAGIC_LINK") => corelib::auth_errors::AuthOperation::MagicLink,
                Some("PASSWORD_RESET") => corelib::auth_errors::AuthOperation::PasswordReset,
                Some("ACCOUNT_LINK") => corelib::auth_errors::AuthOperation::AccountLink,
                _ => return err("operation missing or not an allowed flow"),
            };
            let successful = match v.get("successful").and_then(|x| x.as_bool()) { Some(b) => b, None => return err("successful missing or not a bool") };
            let body = match v.get("body").and_then(|x| x.as_str()) { Some(s) => s, None => return err("body missing or not a string") };
            match corelib::auth_link::interpret_flow_status(successful, body, operation) {
                corelib::auth_link::FlowStatusInterpretation::Proceed =>
                    serde_json::json!({"ok":{"version":1,"verdict":"ok"}}).to_string(),
                corelib::auth_link::FlowStatusInterpretation::Error(m) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"error","message":m}}).to_string(),
            }
        })(),
        "session_refresh_shape_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13, reviewer-corrected): JNI
            // wiring of the NON-CLOCK shape segment of resolveAccessToken's
            // refresh-response handling (session_token::refresh_response_shape,
            // SessionTokenParity JVM locks s01-s23 lineage). Every
            // clock-governed decision (validTokenForOwner, the effective-
            // refresh checks, the reportedExpiry bounds) stays at the
            // original Kotlin execution points. SECRETS: NO existing token
            // crosses IN (code/body/owner only); the renewed access token
            // and any rotated refresh token cross OUT in the reply - same
            // no-logging/no-persistence/no-egress accounting as the other
            // session_token ops, no zeroization guarantee.
            let code = match v.get("code").and_then(|x| x.as_i64()).and_then(|n| i32::try_from(n).ok()) { Some(x) => x, None => return err("code missing or not an i32") };
            let body = match v.get("body") {
                None => None,
                Some(serde_json::Value::Null) => None,
                Some(x) => match x.as_str() { Some(s) => Some(s), None => return err("body not a string or null") },
            };
            let owner = match v.get("owner").and_then(|x| x.as_str()) { Some(s) => s, None => return err("owner missing or not a string") };
            match corelib::session_token::refresh_response_shape(code, body, owner) {
                corelib::session_token::RefreshShape::Shape(f) =>
                    serde_json::json!({"ok":{"version":1,"verdict":"shape","accessToken":f.renewed_token,"rotatedRefresh":f.rotated_refresh,"expiresAt":f.expires_at,"expiresIn":f.expires_in,"userEmail":f.user_email,"anonymous":f.anonymous}}).to_string(),
                corelib::session_token::RefreshShape::ReauthenticationRequired =>
                    serde_json::json!({"ok":{"version":1,"verdict":"reauth"}}).to_string(),
                corelib::session_token::RefreshShape::TemporarilyUnavailable =>
                    serde_json::json!({"ok":{"version":1,"verdict":"unavailable"}}).to_string(),
            }
        })(),
        "supabase_config_resolve_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port supabase_config::resolve_client_settings
            // (SupabaseConfig.kt resolveClientSettings, main@31b88f5
            // lineage; ConfigProbe/ConfigProbe2 JVM corpus). Decision only:
            // the public client pair in, validated settings out.
            let url = match v.get("url") {
                None => return err("url missing"),
                Some(serde_json::Value::Null) => None,
                Some(x) => match x.as_str() { Some(s) => Some(s), None => return err("url not a string or null") },
            };
            let api_key = match v.get("apiKey") {
                None => return err("apiKey missing"),
                Some(serde_json::Value::Null) => None,
                Some(x) => match x.as_str() { Some(s) => Some(s), None => return err("apiKey not a string or null") },
            };
            let r = corelib::supabase_config::resolve_client_settings(url, api_key);
            serde_json::json!({"ok":{"version":1,"url":r.url,"apiKey":r.api_key}}).to_string()
        })(),
        "health_consent_receipt_read_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port auth_prefs_health::HealthAuthPrefs::
            // server_health_consent_generation (AuthPreferences.kt:264
            // getter, main@31b88f5 lineage). Seeds an in-memory ScratchPrefs
            // with the exact keys the Kotlin caller already read, then runs
            // the parity-locked guard verbatim. Decision only: no host
            // preference is read or written here.
            use corelib::auth_prefs_health::HealthAuthPrefs;
            use corelib::prefs_scratch::ScratchPrefs;
            let b = |k: &str| v.get(k).and_then(|x| x.as_bool());
            let l = |k: &str| v.get(k).and_then(|x| x.as_i64());
            let secure_available = match b("secureAvailable") { Some(x) => x, None => return err("secureAvailable missing or not a boolean") };
            let user_id = match v.get("userId") {
                None => return err("userId missing"),
                Some(serde_json::Value::Null) => None,
                Some(x) => match x.as_str() { Some(s) => Some(s.to_string()), None => return err("userId not a string or null") },
            };
            let device_fallback_owner = match v.get("deviceFallbackOwner").and_then(|x| x.as_str()) { Some(s) => s.to_string(), None => return err("deviceFallbackOwner missing or not a string") };
            let session_generation = match l("sessionGeneration") { Some(x) => x, None => return err("sessionGeneration missing or not an integer") };
            let cloud_sync_allowed = match b("cloudSyncAllowed") { Some(x) => x, None => return err("cloudSyncAllowed missing or not a boolean") };
            let health_consent_generation = match l("healthConsentGeneration") { Some(x) => x, None => return err("healthConsentGeneration missing or not an integer") };
            let pending_revoke = match b("pendingRevoke") { Some(x) => x, None => return err("pendingRevoke missing or not a boolean") };
            let receipt_server = match l("receiptServer") { Some(x) => x, None => return err("receiptServer missing or not an integer") };
            let receipt_local = match l("receiptLocal") { Some(x) => x, None => return err("receiptLocal missing or not an integer") };
            let receipt_session = match l("receiptSession") { Some(x) => x, None => return err("receiptSession missing or not an integer") };
            let mut scratch = ScratchPrefs::open_in_memory();
            {
                let probe = HealthAuthPrefs { prefs: &mut scratch, secure_available, secure_user_id: user_id.clone(), device_fallback_owner: device_fallback_owner.clone(), owner_wiped: false, live_user_id: None, live_owner_wiped: None };
                let owner = probe.effective_user_id();
                probe.prefs.edit()
                    .put_bool(&format!("cloud_sync_allowed_v2_{owner}"), cloud_sync_allowed)
                    .put_long(&format!("health_consent_generation_{owner}"), health_consent_generation)
                    .put_bool(&format!("health_sync_pending_revoke_{owner}"), pending_revoke)
                    .put_long(&format!("health_sync_receipt_server_{owner}"), receipt_server)
                    .put_long(&format!("health_sync_receipt_local_{owner}"), receipt_local)
                    .put_long(&format!("health_sync_receipt_session_{owner}"), receipt_session)
                    .put_long("session_generation_v3", session_generation)
                    .commit();
            }
            let hp = HealthAuthPrefs { prefs: &mut scratch, secure_available, secure_user_id: user_id, device_fallback_owner, owner_wiped: false, live_user_id: None, live_owner_wiped: None };
            let receipt = hp.server_health_consent_generation();
            match receipt {
                Some(g) => serde_json::json!({"ok":{"version":1,"receipt":g}}).to_string(),
                None => serde_json::json!({"ok":{"version":1,"receipt":null}}).to_string(),
            }
        })(),
        "health_consent_receipt_save_guard_v1" => (|| {
            // NEW IMPLEMENTATION (2026-11-13): JNI wiring of the existing
            // parity port auth_prefs_health::HealthAuthPrefs::
            // save_server_health_consent_receipt guards (AuthPreferences.kt
            // saveServerHealthConsentReceipt, main@31b88f5 lineage). Returns
            // the guard decision ONLY; the caller applies the exact key
            // mutation to the real SharedPreferences and returns the real
            // commit() result, exactly as the source does.
            use corelib::auth_prefs_health::HealthAuthPrefs;
            use corelib::prefs_scratch::ScratchPrefs;
            let b = |k: &str| v.get(k).and_then(|x| x.as_bool());
            let l = |k: &str| v.get(k).and_then(|x| x.as_i64());
            let secure_available = match b("secureAvailable") { Some(x) => x, None => return err("secureAvailable missing or not a boolean") };
            let user_id = match v.get("userId") {
                None => return err("userId missing"),
                Some(serde_json::Value::Null) => None,
                Some(x) => match x.as_str() { Some(s) => Some(s.to_string()), None => return err("userId not a string or null") },
            };
            let device_fallback_owner = match v.get("deviceFallbackOwner").and_then(|x| x.as_str()) { Some(s) => s.to_string(), None => return err("deviceFallbackOwner missing or not a string") };
            let session_generation = match l("sessionGeneration") { Some(x) => x, None => return err("sessionGeneration missing or not an integer") };
            let cloud_sync_allowed = match b("cloudSyncAllowed") { Some(x) => x, None => return err("cloudSyncAllowed missing or not a boolean") };
            let health_consent_generation = match l("healthConsentGeneration") { Some(x) => x, None => return err("healthConsentGeneration missing or not an integer") };
            let pending_revoke = match b("pendingRevoke") { Some(x) => x, None => return err("pendingRevoke missing or not a boolean") };
            let owner = match v.get("owner").and_then(|x| x.as_str()) { Some(s) => s.to_string(), None => return err("owner missing or not a string") };
            let session = match l("session") { Some(x) => x, None => return err("session missing or not an integer") };
            let local_generation = match l("localGeneration") { Some(x) => x, None => return err("localGeneration missing or not an integer") };
            let server_generation = match l("serverGeneration") { Some(x) => x, None => return err("serverGeneration missing or not an integer") };
            let enabled = match b("enabled") { Some(x) => x, None => return err("enabled missing or not a boolean") };
            let owner_wiped = match b("ownerWiped") { Some(x) => x, None => return err("ownerWiped missing or not a boolean") };
            let mut scratch = ScratchPrefs::open_in_memory();
            {
                let probe = HealthAuthPrefs { prefs: &mut scratch, secure_available, secure_user_id: user_id.clone(), device_fallback_owner: device_fallback_owner.clone(), owner_wiped, live_user_id: None, live_owner_wiped: None };
                let effective = probe.effective_user_id();
                probe.prefs.edit()
                    .put_bool(&format!("cloud_sync_allowed_v2_{effective}"), cloud_sync_allowed)
                    .put_long(&format!("health_consent_generation_{effective}"), health_consent_generation)
                    .put_bool(&format!("health_sync_pending_revoke_{effective}"), pending_revoke)
                    .put_long("session_generation_v3", session_generation)
                    .commit();
            }
            let mut hp = HealthAuthPrefs { prefs: &mut scratch, secure_available, secure_user_id: user_id, device_fallback_owner, owner_wiped, live_user_id: None, live_owner_wiped: None };
            let allowed = hp.save_server_health_consent_receipt(&owner, session, local_generation, server_generation, enabled);
            serde_json::json!({"ok":{"version":1,"allowed":allowed}}).to_string()
        })(),
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
        "calculate_phase_for_date" => {
            let s = &v["settings"];
            let settings = PatternSettings {
                average_cycle_length: s["averageCycleLength"].as_i64().unwrap_or(28) as i32,
                period_duration: s["periodDuration"].as_i64().unwrap_or(5) as i32,
                luteal_phase_days: s["lutealPhaseDays"].as_i64().unwrap_or(14) as i32,
                last_period_start_date: s["lastPeriodStartDate"].as_str().unwrap_or("").into(),
            };
            let date = v["date"].as_str().unwrap_or("");
            let parsed = chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d");
            match parsed {
                Ok(d) => {
                    let phase = pattern_engine::calculate_phase_for_date(d, &settings);
                    serde_json::json!({"ok": {"phase": phase.kotlin_name()}}).to_string()
                }
                Err(_) => serde_json::json!({"error": "invalid date"}).to_string(),
            }
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
        "cycle_screen_state" => {
            use corelib::cycle_math::parse_local_date_iso;
            use corelib::cycle_screen_state::{build_cycle_screen_state, CycleScreenInput};
            let st = &v["settings"];
            if !st.is_object() {
                return err("settings must be an object".into());
            }
            // No silent defaults, no silent i64->i32 truncation: every
            // settings field must be present and fit i32 (the Kotlin side
            // always sends real stored Ints; a missing/mistyped field is a
            // contract bug and must fail loudly).
            let req_i32 = |k: &str| -> Result<i32, String> {
                match st[k].as_i64() {
                    Some(n) => i32::try_from(n).map_err(|_| format!("settings.{k} out of i32 range")),
                    None => Err(format!("settings.{k} missing or not an integer")),
                }
            };
            let (average_cycle_length, period_duration, luteal_phase_days) =
                match (req_i32("averageCycleLength"), req_i32("periodDuration"), req_i32("lutealPhaseDays")) {
                    (Ok(a), Ok(p), Ok(l)) => (a, p, l),
                    (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => return err(&e),
                };
            let last_period_start_date = match st["lastPeriodStartDate"].as_str() {
                Some(s) => s.to_string(),
                None => return err("settings.lastPeriodStartDate missing or not a string"),
            };
            let today = match v["today"].as_str().and_then(parse_local_date_iso) {
                Some(d) => d,
                None => return err("today must be ISO yyyy-MM-dd"),
            };
            let selected = match v["selectedDate"].as_str().and_then(parse_local_date_iso) {
                Some(d) => d,
                None => return err("selectedDate must be ISO yyyy-MM-dd"),
            };
            if !(v["dateChoice"].is_string() || v["dateChoice"].is_null())
                || !(v["cycleLengthChoice"].is_string() || v["cycleLengthChoice"].is_null())
            {
                return err("dateChoice/cycleLengthChoice must be string or null");
            }
            let input = CycleScreenInput {
                last_period_start_date,
                average_cycle_length,
                period_duration,
                luteal_phase_days,
                date_choice: v["dateChoice"].as_str().map(Into::into),
                cycle_length_choice: v["cycleLengthChoice"].as_str().map(Into::into),
                today,
                selected_date: selected,
            };
            // Full-parser admission: a Java-valid anchor outside the native
            // range (or an overflowing countdown step) is a fallback, not a
            // fake countdown; a Java-invalid anchor is handled inside.
            let s = match build_cycle_screen_state(&input) {
                Some(s) => s,
                None => return err("anchor outside native range".into()),
            };
            let pred = |p: &Option<corelib::cycle_math::DayPrediction>| match p {
                Some(p) => serde_json::json!({
                    "date": p.date.to_string(),
                    "dayOfCycle": p.day_of_cycle,
                    "phase": p.phase.kotlin_name(), // Kotlin enum entry name, e.g. "LUTEAL" (not Debug "Luteal")
                    "phaseTitle": p.phase.kotlin_title(),
                    "phaseSubtitle": p.phase.kotlin_subtitle(),
                    "phaseDescription": p.phase.kotlin_description(),
                    "phaseAdvice": p.phase.kotlin_advice(),
                    "phaseColorHex": p.phase.kotlin_color_hex(),
                    "isPeriod": p.is_period,
                    "isFertile": p.is_fertile,
                    "isOvulation": p.is_ovulation,
                    "fertilityConfidence": p.fertility_confidence,
                    "pregnancyChance": p.pregnancy_chance,
                }),
                None => serde_json::Value::Null,
            };
            serde_json::json!({"ok": {
                "hasCycleAnchor": s.has_cycle_anchor,
                "cycleStartIsApproximate": s.cycle_start_is_approximate,
                "daysUntilNextPeriod": s.days_until_next_period,
                "nextPeriodDate": s.next_period_date.map(|d| d.to_string()),
                "daysUntilOvulation": s.days_until_ovulation,
                "isPeriodActiveToday": s.is_period_active_today,
                "todayPrediction": pred(&s.today_prediction),
                "selectedDatePrediction": pred(&s.selected_prediction),
                "selectedDayOfCycleText": s.selected_day_of_cycle_text,
                "daysUntilPeriodFromSelected": s.days_until_period_from_selected,
                "selectedDayTitle": s.selected_day_title,
                "disclaimer": s.disclaimer,
            }})
            .to_string()
        }
        "cycle_month_predictions" => {
            use corelib::cycle_math::parse_local_date_iso;
            use corelib::cycle_screen_state::{build_month_predictions, MonthPredictionsInput};
            let st = &v["settings"];
            if !st.is_object() {
                return err("settings must be an object".into());
            }
            // Same strict settings contract as cycle_screen_state: no
            // silent defaults, no silent i64->i32 truncation.
            let req_i32 = |k: &str| -> Result<i32, String> {
                match st[k].as_i64() {
                    Some(n) => i32::try_from(n).map_err(|_| format!("settings.{k} out of i32 range")),
                    None => Err(format!("settings.{k} missing or not an integer")),
                }
            };
            let (average_cycle_length, period_duration, luteal_phase_days) =
                match (req_i32("averageCycleLength"), req_i32("periodDuration"), req_i32("lutealPhaseDays")) {
                    (Ok(a), Ok(p), Ok(l)) => (a, p, l),
                    (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => return err(&e),
                };
            let last_period_start_date = match st["lastPeriodStartDate"].as_str() {
                Some(s) => s.to_string(),
                None => return err("settings.lastPeriodStartDate missing or not a string"),
            };
            let today = match v["today"].as_str().and_then(parse_local_date_iso) {
                Some(d) => d,
                None => return err("today must be ISO yyyy-MM-dd"),
            };
            // Kotlin YearMonth.toString() is "yyyy-MM"; parse as the first
            // of the month with the same strict ISO rules as full dates.
            let month_first = match v["month"]
                .as_str()
                .and_then(|m| parse_local_date_iso(&format!("{m}-01")))
            {
                Some(d) => d,
                None => return err("month must be yyyy-MM"),
            };
            if !(v["dateChoice"].is_string() || v["dateChoice"].is_null())
                || !(v["cycleLengthChoice"].is_string() || v["cycleLengthChoice"].is_null())
            {
                return err("dateChoice/cycleLengthChoice must be string or null");
            }
            let input = MonthPredictionsInput {
                last_period_start_date,
                average_cycle_length,
                period_duration,
                luteal_phase_days,
                date_choice: v["dateChoice"].as_str().map(Into::into),
                cycle_length_choice: v["cycleLengthChoice"].as_str().map(Into::into),
                today,
                month_first_day: month_first,
            };
            // None covers an out-of-range anchor AND a displayed month at
            // the native range edge - both are Kotlin fallbacks.
            let m = match build_month_predictions(&input) {
                Some(m) => m,
                None => return err("date outside native range".into()),
            };
            let predictions: Vec<serde_json::Value> = m
                .predictions
                .iter()
                .map(|(d, p)| {
                    let pj = match p {
                        Some(p) => prediction_json(p),
                        None => serde_json::Value::Null,
                    };
                    serde_json::json!({"date": d.to_string(), "prediction": pj})
                })
                .collect();
            serde_json::json!({"ok": {
                "month": v["month"].as_str().unwrap(),
                "hasCycleAnchor": m.has_cycle_anchor,
                "predictions": predictions,
            }})
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
        // M7 accepted wire contract: strict Kotlin enum NAME (SafetyEngineNativeJniTest.nativeSoresSeekProfessionalAdviceStrictName).
        assert!(r.contains("SEEK_PROFESSIONAL_ADVICE") && r.contains("isRedFlag\":true"), "{r}");
        let r = super::dispatch("nope", "{}");
        assert!(r.contains("unknown contract"), "{r}");
    }
}

#[cfg(test)]
mod tests {
    // Envelope smoke checks reuse the executed JVM expectations (LifecycleParity
    // gate_returning_complete; VerifiedProfileParity vp01; ReconcileParity rc01,
    // hr01, es01).
    /// Exact-decimal contract across the FFI JSON boundary: balances travel
    /// as exact decimal strings, never floats (3 decimals, large values).
    #[test]
    fn decimal_balances_cross_ffi_exactly() {
        let payload = serde_json::json!({
            "owner": "u1",
            "econBody": "[{\"user_id\":\"u1\",\"status\":\"active\",\"coin_balance\":363.575,\"lifetime_earned\":1234567.891,\"lifetime_spent\":0.001,\"tier\":\"free\",\"rank\":\"lumen\",\"active_frame\":null,\"active_avatar_art\":null,\"last_mined_date\":null,\"unlocked_items\":[],\"artifacts\":[]}]",
            "profBody": "{\"user_id\":\"u1\",\"status\":\"ACTIVE\"}",
            "nowIso": "2026-11-02T12:00:00Z"
        }).to_string();
        let out = super::dispatch("verified_profile_parse", &payload);
        assert!(out.contains(r#""coinBalance":"363.575""#), "3-decimal balance exact: {out}");
        assert!(out.contains(r#""lifetimeEarned":"1234567.891""#), "large balance exact: {out}");
        assert!(out.contains(r#""lifetimeSpent":"0.001""#), "small balance exact: {out}");
    }

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

/// Test-only handle on the dispatcher (the JNI entry points need a JVM).
#[doc(hidden)]
pub fn dispatch_for_test(contract: &str, payload: &str) -> String {
    dispatch(contract, payload)
}

/// Strict local planner endpoint. No fallback, DB or clock access. Java host
/// owns current identity exclusion and transaction throughout invocation.
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativePlan(mut env:JNIEnv,_class:JClass,payload:JString)->jstring {
 let payload:String=match env.get_string(&payload){Ok(s)=>s.into(),Err(_)=>return std::ptr::null_mut()};
 match std::panic::catch_unwind(||corelib::local_plan_wire::plan(&payload)){Ok(Ok(result))=>out(&mut env,&result),_=>std::ptr::null_mut()}
}

mod onboarding_storage_jni;
/// Stateful boundary ONLY for a private admitted completion transaction session.
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeComplete<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>)->jint {
    onboarding_storage_jni::execute(env,session)
}

mod settings_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeSettings<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>,reads:jint,mutations:jint)->jint {
    settings_storage_jni::execute(env,session,reads,mutations)
}

mod batch_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeBatch<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>,reads:jint,mutations:jint)->jint {
    batch_storage_jni::execute(env,session,reads,mutations)
}

mod outbox_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeOutbox<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>,mode:jint)->jint {
    outbox_storage_jni::execute(env,session,mode)
}

mod conflict_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeConflict<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>)->jint {
    conflict_storage_jni::execute(env,session)
}

mod remote_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeRemote<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>)->jint {remote_storage_jni::execute(env,session)}

mod resolution_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeResolution<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>)->jint {
    resolution_storage_jni::execute(env,session)
}

mod erasure_storage_jni;
#[no_mangle]
pub extern "system" fn Java_com_cyclecare_app_data_local_rust_RustTrackNative_nativeErase<'local>(env:JNIEnv<'local>,_class:JClass<'local>,session:jni::objects::JObject<'local>)->jint {
    erasure_storage_jni::execute(env,session)
}
