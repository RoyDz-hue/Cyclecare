//! Rust port of the SupabaseAuthService email-link / auth-code completion tree
//! (completeEmailLink, completeAuthCode, saveSession, emailIsConfirmed, readUser,
//! operationForAction, successMessageForAction, clearPendingAuthFlow) at 31b88f5.
//!
//! Grounding: the verbatim Kotlin service was executed on the JVM against AOSP
//! android.net.Uri/UriCodec (compiled from android-14.0.0_r1 source), AOSP
//! android.util.Base64, AOSP org.json and a programmable okhttp stub; 16
//! end-to-end rows (c01-c16 in fixtures/oracle/auth/results_authcb.tsv) lock the
//! observable outcomes: result class + message, session writes, request count.
//!
//! AuthPreferences state model mirrors the REAL AuthPreferences (security/
//! AuthPreferences.kt): clearSession clears tokens/expiry/pkce/user/pending
//! fields, sets accountMode="ANONYMOUS" and bumps session_generation_v3 via
//! checked add (Math.addExact). Note: the JVM probe's prefs stub cleared
//! accountMode to null instead; the c-rows never route through clearSession so
//! their locked values are unaffected.
//!
//! Transport and clock are injected seams, mirroring PaymentUseCase's
//! constructor-injected seams (learning: test at the seams production owns).
//! Network exception -> catch-all -> operation-appropriate copy, never a
//! bad-credentials message, never an attempt consumed.

use crate::auth_errors::{fallback, for_response, AuthOperation};
use crate::auth_uri::parse_auth_callback_url;
use crate::sync_codec::{aosp_opt_string, JsonParser, JsonValue};

pub const AUTH_ACTION_GUEST_LINK: &str = "GUEST_LINK";
pub const AUTH_ACTION_PASSWORD_RESET: &str = "PASSWORD_RESET";
pub const AUTH_ACTION_GOOGLE_LINK: &str = "GOOGLE_LINK";
pub const AUTH_ACTION_GOOGLE: &str = "GOOGLE";
pub const AUTH_ACTION_SIGN_UP: &str = "SIGN_UP";
pub const AUTH_ACTION_MAGIC_LINK: &str = "MAGIC_LINK";

const MSG_INVALID_LINK: &str = "This is not a valid CycleCare sign-in link.";
const MSG_CONFLICTING: &str = "This CycleCare sign-in link contains conflicting session data. Request a new link.";
const MSG_UNCONFIRMED: &str = "Email verification is not complete yet. Open the newest link from your inbox.";
const MSG_DIFFERENT_ACCOUNT: &str =
    "This link belongs to a different account. Request a new CycleCare link on this device.";
const MSG_VERIFY_FAILED: &str =
    "We couldn't verify this CycleCare link right now. Check your connection and try again.";
const MSG_ALREADY_SIGNED_IN: &str =
    "An account is already signed in on this device. Sign out before switching accounts.";
const MSG_VERIFY_EMAIL_FIRST: &str =
    "Please verify your email using the CycleCare link before continuing.";
const MSG_SECURE_UNAVAILABLE: &str = "Secure credential storage is unavailable on this device. Sign-in, cloud sync, and app lock are disabled until device security is restored.";

/// Kotlin Char.isWhitespace for the chars production links can carry
/// (ASCII + the Unicode spaces Kotlin counts; per WsProbe learning NBSP,
/// NNBSP and thin space count as whitespace in Kotlin).
fn kotlin_is_blank(s: &str) -> bool {
    s.chars().all(|c| {
        matches!(
            c,
            ' ' | '\t' | '\n' | '\r' | '\u{0B}' | '\u{0C}' | '\u{85}' | '\u{A0}' | '\u{1680}'
                | '\u{2000}'..='\u{200A}' | '\u{2028}' | '\u{2029}' | '\u{202F}' | '\u{205F}'
                | '\u{3000}'
        )
    })
}

/// Kotlin String.ifBlank { null }.
fn if_blank(s: String) -> Option<String> {
    if kotlin_is_blank(&s) {
        None
    } else {
        Some(s)
    }
}

// ---------- AOSP JSONObject opt helpers not already in sync_codec ----------

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// AOSP optJSONObject: the value only when it is a JSONObject, else null.
fn opt_object<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj_get(obj, key) {
        Some(v @ JsonValue::Object(_)) => Some(v),
        _ => None,
    }
}

/// AOSP optBoolean: Boolean -> value; String -> Boolean.parseBoolean
/// ("true" ignoring ASCII case); anything else (incl. numbers) -> fallback.
fn opt_boolean(obj: &JsonValue, key: &str, fallback: bool) -> bool {
    match obj_get(obj, key) {
        Some(JsonValue::Bool(b)) => *b,
        Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
        _ => fallback,
    }
}

/// AOSP optLong: Number -> longValue (Double truncated toward zero);
/// String -> Long.parseLong (no trimming, rejects junk); else fallback.
fn opt_long(obj: &JsonValue, key: &str, fallback: i64) -> i64 {
    match obj_get(obj, key) {
        Some(JsonValue::Int(v)) => *v as i64,
        Some(JsonValue::Long(v)) => *v,
        Some(JsonValue::Double(v)) => v.trunc() as i64,
        Some(JsonValue::Str(s)) => s.parse::<i64>().unwrap_or(fallback),
        _ => fallback,
    }
}

// ---------- session preference state ----------

/// AuthPreferences fields the link/code tree reads or writes, with real
/// clearSession semantics (see module doc).
#[derive(Debug, Clone, Default)]
pub struct AuthPrefsState {
    pub is_secure_storage_available: bool,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub session_expires_at_seconds: i64,
    pub user_id: Option<String>,
    pub user_email: Option<String>,
    pub account_mode: Option<String>,
    pub pending_email: Option<String>,
    pub pending_auth_action: Option<String>,
    pub google_pkce_verifier: Option<String>,
    pub is_pending_guest_email_link: bool,
    pub needs_password_setup: bool,
    pub session_generation: i64,
}

impl AuthPrefsState {
    pub fn fresh() -> Self {
        AuthPrefsState {
            is_secure_storage_available: true,
            session_generation: 0,
            ..Default::default()
        }
    }

    /// Real AuthPreferences.clearSession: drops tokens/user/pending state,
    /// sets accountMode back to "ANONYMOUS", bumps the generation (checked).
    pub fn clear_session(&mut self) {
        self.session_generation = self
            .session_generation
            .checked_add(1)
            .expect("session generation overflow");
        self.access_token = None;
        self.refresh_token = None;
        self.session_expires_at_seconds = 0;
        self.google_pkce_verifier = None;
        self.user_id = None;
        self.user_email = None;
        self.pending_email = None;
        self.pending_auth_action = None;
        self.is_pending_guest_email_link = false;
        self.needs_password_setup = false;
        self.account_mode = Some("ANONYMOUS".to_string());
    }

    fn clear_pending_auth_flow(&mut self, clear_pending_email: bool) {
        self.google_pkce_verifier = None;
        self.pending_auth_action = None;
        if clear_pending_email {
            self.pending_email = None;
            self.is_pending_guest_email_link = false;
        }
    }
}

// ---------- results ----------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthUser {
    pub id: String,
    pub email: Option<String>,
    pub email_confirmed_at: Option<String>,
    pub is_anonymous: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthOutcome {
    Success { user: AuthUser, message: String },
    Error(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponse {
    pub successful: bool,
    pub body: String,
}

/// Injected transport: method, url, optional JSON body. Err = network failure
/// (the production catch-all path).
pub type Transport<'a> = dyn FnMut(&str, &str, Option<&str>) -> Result<HttpResponse, String> + 'a;

// ---------- pure helpers (verbatim ports) ----------

pub fn email_is_confirmed(user: &JsonValue) -> bool {
    // AOSP optString turns an explicit JSON null into the literal "null",
    // which is NOT blank; mirrored by aosp_opt_string.
    !kotlin_is_blank(&aosp_opt_string(user, "email_confirmed_at", ""))
        || !kotlin_is_blank(&aosp_opt_string(user, "confirmed_at", ""))
}

pub fn read_user(json: &JsonValue, fallback_email: Option<&str>) -> AuthUser {
    let email = if_blank(aosp_opt_string(json, "email", ""))
        .or_else(|| fallback_email.map(|e| e.to_string()));
    let email_confirmed_at = if_blank(aosp_opt_string(json, "email_confirmed_at", ""))
        .or_else(|| if_blank(aosp_opt_string(json, "confirmed_at", "")));
    AuthUser {
        id: aosp_opt_string(json, "id", ""),
        email,
        email_confirmed_at,
        is_anonymous: opt_boolean(json, "is_anonymous", false),
    }
}

fn operation_for_action(action: &str) -> AuthOperation {
    match action {
        AUTH_ACTION_SIGN_UP => AuthOperation::SignUp,
        AUTH_ACTION_MAGIC_LINK => AuthOperation::MagicLink,
        AUTH_ACTION_PASSWORD_RESET => AuthOperation::PasswordReset,
        AUTH_ACTION_GUEST_LINK | AUTH_ACTION_GOOGLE_LINK => AuthOperation::AccountLink,
        _ => AuthOperation::Google,
    }
}

fn success_message_for_action(action: &str) -> &'static str {
    match action {
        AUTH_ACTION_SIGN_UP => "Your email is verified and your CycleCare account is ready.",
        AUTH_ACTION_MAGIC_LINK => "You're signed in to CycleCare.",
        AUTH_ACTION_PASSWORD_RESET => "Your reset link is verified. Set a new password to finish.",
        AUTH_ACTION_GUEST_LINK => {
            "Your guest data is now linked to your verified email. Set a password or use email sign-in."
        }
        AUTH_ACTION_GOOGLE_LINK => {
            "Your Google account is linked. Your guest progress stays with this account."
        }
        _ => "Your Google account is verified and signed in to CycleCare.",
    }
}

pub fn save_session(prefs: &mut AuthPrefsState, json: &JsonValue, mode: &str, now_seconds: i64) {
    let user = opt_object(json, "user").unwrap_or(json);
    prefs.account_mode = Some(mode.to_string());
    let uid = aosp_opt_string(user, "id", "");
    prefs.user_id = if_blank(uid).or_else(|| prefs.user_id.clone());
    prefs.user_email = if_blank(aosp_opt_string(user, "email", ""));
    prefs.access_token = if_blank(aosp_opt_string(json, "access_token", ""));
    prefs.refresh_token = if_blank(aosp_opt_string(json, "refresh_token", ""));
    let expires_in = opt_long(json, "expires_in", 3600);
    prefs.session_expires_at_seconds = opt_long(json, "expires_at", now_seconds + expires_in);
}

// ---------- decision trees ----------

/// Verbatim port of completeEmailLink. `auth_api_url` mirrors the service's
/// injected base URL; the transport receives the exact GET the service builds.
pub fn complete_email_link(
    prefs: &mut AuthPrefsState,
    url: &str,
    auth_api_url: &str,
    now_seconds: i64,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let parameters = match parse_auth_callback_url(url) {
        Some(p) => p,
        None => return AuthOutcome::Error(MSG_INVALID_LINK.to_string()),
    };
    let value = |key: &str| -> Option<&str> {
        parameters
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    };

    let link_error = value("error_description").or_else(|| value("error"));
    if let Some(e) = link_error {
        if !kotlin_is_blank(e) {
            return AuthOutcome::Error(for_response(&e.replace('+', " "), AuthOperation::Callback));
        }
    }
    let auth_code = value("code").unwrap_or("");
    if !auth_code.is_empty() {
        if value("access_token").is_some() || value("refresh_token").is_some() {
            return AuthOutcome::Error(MSG_CONFLICTING.to_string());
        }
        return complete_auth_code(prefs, auth_code, auth_api_url, now_seconds, transport);
    }
    let access_token = value("access_token").unwrap_or("");
    let refresh_token = value("refresh_token").unwrap_or("");
    if access_token.is_empty() || refresh_token.is_empty() {
        return AuthOutcome::Error(for_response("expired link", AuthOperation::Callback));
    }

    // try { ... } catch -> "couldn't verify" copy
    let user_url = format!("{auth_api_url}/user");
    let response = match transport("GET", &user_url, None) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_VERIFY_FAILED.to_string()),
    };
    if !response.successful {
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::Callback));
    }
    let user = match JsonParser::new(&response.body).parse() {
        Ok(v) => v,
        Err(_) => return AuthOutcome::Error(MSG_VERIFY_FAILED.to_string()),
    };
    if kotlin_is_blank(&aosp_opt_string(&user, "id", "")) {
        return AuthOutcome::Error(fallback(AuthOperation::Callback).to_string());
    }
    let is_anonymous = opt_boolean(&user, "is_anonymous", false);
    if !is_anonymous && !email_is_confirmed(&user) {
        return AuthOutcome::Error(MSG_UNCONFIRMED.to_string());
    }
    if prefs.is_pending_guest_email_link
        && prefs.user_id.as_deref().map(|u| !u.is_empty()).unwrap_or(false)
        && prefs.user_id.as_deref() != Some(aosp_opt_string(&user, "id", "").as_str())
    {
        return AuthOutcome::Error(MSG_DIFFERENT_ACCOUNT.to_string());
    }

    prefs.access_token = Some(access_token.to_string());
    prefs.refresh_token = Some(refresh_token.to_string());
    prefs.session_expires_at_seconds = value("expires_at")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or_else(|| {
            now_seconds + value("expires_in").and_then(|v| v.parse::<i64>().ok()).unwrap_or(3600)
        });
    prefs.user_id = Some(aosp_opt_string(&user, "id", ""));
    let email = if_blank(aosp_opt_string(&user, "email", ""))
        .or_else(|| prefs.pending_email.clone())
        .unwrap_or_default();
    prefs.user_email = if email.is_empty() { None } else { Some(email.clone()) };
    prefs.account_mode = Some(if is_anonymous { "ANONYMOUS" } else { "EMAIL" }.to_string());
    let linked_guest = prefs.is_pending_guest_email_link && !is_anonymous;
    prefs.is_pending_guest_email_link = false;
    prefs.pending_email = None;
    let password_recovery = value("type").map(|t| t.eq_ignore_ascii_case("recovery")).unwrap_or(false)
        || prefs.pending_auth_action.as_deref() == Some(AUTH_ACTION_PASSWORD_RESET);
    prefs.clear_pending_auth_flow(false);
    prefs.needs_password_setup = linked_guest || password_recovery;
    AuthOutcome::Success {
        user: read_user(&user, Some(email.as_str())),
        message: if linked_guest {
            success_message_for_action(AUTH_ACTION_GUEST_LINK).to_string()
        } else if password_recovery {
            success_message_for_action(AUTH_ACTION_PASSWORD_RESET).to_string()
        } else {
            "Your CycleCare email is verified and you are signed in.".to_string()
        },
    }
}

/// Verbatim port of completeAuthCode (PKCE token exchange branch).
fn complete_auth_code(
    prefs: &mut AuthPrefsState,
    auth_code: &str,
    auth_api_url: &str,
    now_seconds: i64,
    transport: &mut Transport,
) -> AuthOutcome {
    let verifier = match &prefs.google_pkce_verifier {
        Some(v) => v.clone(),
        None => return AuthOutcome::Error(for_response("expired code", AuthOperation::Callback)),
    };
    let action = prefs
        .pending_auth_action
        .clone()
        .unwrap_or_else(|| {
            if prefs.is_pending_guest_email_link {
                AUTH_ACTION_GUEST_LINK.to_string()
            } else {
                AUTH_ACTION_GOOGLE.to_string()
            }
        });
    let operation = operation_for_action(&action);
    let linking_guest = (action == AUTH_ACTION_GUEST_LINK && prefs.is_pending_guest_email_link)
        || action == AUTH_ACTION_GOOGLE_LINK;
    let existing_user_id = prefs.user_id.clone();
    if existing_user_id.as_deref().map(|u| !u.is_empty()).unwrap_or(false) && !linking_guest {
        prefs.clear_pending_auth_flow(false);
        return AuthOutcome::Error(MSG_ALREADY_SIGNED_IN.to_string());
    }
    let body = format!(
        "{{\"auth_code\":\"{}\",\"code_verifier\":\"{}\"}}",
        auth_code, verifier
    );
    let token_url = format!("{auth_api_url}/token?grant_type=pkce");
    let response = match transport("POST", &token_url, Some(&body)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(fallback(operation).to_string()),
    };
    if !response.successful {
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(for_response(&response.body, operation));
    }
    let json = match JsonParser::new(&response.body).parse() {
        Ok(v) => v,
        Err(_) => return AuthOutcome::Error(fallback(operation).to_string()),
    };
    let empty = JsonValue::Object(vec![]);
    let user = opt_object(&json, "user").unwrap_or(&empty);
    let user_id = aosp_opt_string(user, "id", "");
    if kotlin_is_blank(&aosp_opt_string(&json, "access_token", "")) || kotlin_is_blank(&user_id) {
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(fallback(operation).to_string());
    }
    if linking_guest
        && existing_user_id.as_deref().map(|u| !u.is_empty()).unwrap_or(false)
        && existing_user_id.as_deref() != Some(user_id.as_str())
    {
        // A link callback must upgrade THIS account, never switch to another one.
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(MSG_DIFFERENT_ACCOUNT.to_string());
    }
    if !email_is_confirmed(user) {
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(MSG_VERIFY_EMAIL_FIRST.to_string());
    }
    save_session(prefs, &json, "EMAIL", now_seconds);
    let email = if_blank(aosp_opt_string(user, "email", ""))
        .or_else(|| prefs.pending_email.clone())
        .unwrap_or_default();
    prefs.is_pending_guest_email_link = false;
    prefs.clear_pending_auth_flow(true);
    prefs.needs_password_setup =
        action == AUTH_ACTION_GUEST_LINK || action == AUTH_ACTION_PASSWORD_RESET;
    AuthOutcome::Success {
        user: read_user(user, Some(email.as_str())),
        message: success_message_for_action(&action).to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    struct Canned {
        queued: std::collections::VecDeque<HttpResponse>,
        requests: usize,
    }
    impl Canned {
        fn transport(&mut self) -> impl FnMut(&str, &str, Option<&str>) -> Result<HttpResponse, String> + '_ {
            move |_m, _u, _b| {
                self.requests += 1;
                self.queued
                    .pop_front()
                    .ok_or_else(|| "probe: no response queued".to_string())
            }
        }
    }

    fn resp(ok: bool, body: &str) -> HttpResponse {
        HttpResponse { successful: ok, body: body.to_string() }
    }

    struct Row {
        id: String,
        url: String,
        setup: fn(&mut AuthPrefsState),
        queued: Vec<HttpResponse>,
        expect: Vec<(String, String)>,
        now: i64,
    }

    fn load_rows() -> Vec<Row> {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("fixtures/oracle/auth/results_authcb.tsv");
        let text = std::fs::read_to_string(p).unwrap();
        let mut rows = Vec::new();
        for line in text.lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.get(1) != Some(&"link") {
                continue;
            }
            let id = cols[0].to_string();
            let kv = |prefix: &str| -> String {
                cols.iter()
                    .find_map(|c| c.strip_prefix(prefix))
                    .unwrap_or("")
                    .to_string()
            };
            let expect: Vec<(String, String)> = cols[2..]
                .iter()
                .filter_map(|c| c.split_once('='))
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect();
            let url = kv("url=");
            let setup: fn(&mut AuthPrefsState) = match id.as_str() {
                "c04" => |p: &mut AuthPrefsState| {
                    p.is_pending_guest_email_link = true;
                    p.user_id = Some("owner-x".into());
                },
                "c05" => |p: &mut AuthPrefsState| {
                    p.is_pending_guest_email_link = true;
                    p.user_id = Some("u1".into());
                    p.pending_email = Some("g@h.i".into());
                },
                "c14" => |p: &mut AuthPrefsState| {
                    p.google_pkce_verifier = Some("v123".into());
                    p.pending_auth_action = Some("GOOGLE".into());
                },
                "c16" => |p: &mut AuthPrefsState| {
                    p.google_pkce_verifier = Some("v123".into());
                    p.pending_auth_action = Some("GOOGLE".into());
                    p.user_id = Some("owner-y".into());
                },
                _ => |_| {},
            };
            let queued = match id.as_str() {
                "c01" | "c06" | "c10" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":"2026-01-01T00:00:00Z","is_anonymous":false}"#)],
                "c02" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","is_anonymous":false}"#)],
                "c03" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","confirmed_at":"2026-01-01","is_anonymous":false}"#)],
                "c04" => vec![resp(true, r#"{"id":"u-other","email":"a@b.c","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c05" => vec![resp(true, r#"{"id":"u1","email":"","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c09" => vec![resp(false, r#"{"code":401,"msg":"bad jwt"}"#)],
                "c11" => vec![],
                "c14" => vec![resp(true, r#"{"access_token":"T1","refresh_token":"R2","expires_in":3600,"user":{"id":"u9","email":"g@g.co","email_confirmed_at":"x","is_anonymous":false}}"#)],
                "c15" => vec![resp(true, r#"{"access_token":"T1"}"#)],
                "c16" => vec![resp(true, r#"{"access_token":"T1","user":{"id":"u9","email_confirmed_at":"x"}}"#)],
                "c17" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":null,"confirmed_at":null,"is_anonymous":false}"#)],
                "c18" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":null,"is_anonymous":false}"#)],
                "c19" => vec![resp(true, r#"{"id":"","email":"a@b.c","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c20" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":12345,"is_anonymous":false}"#)],
                _ => vec![],
            };
            // expires fallback rows: pin now so now+3600 matches the recorded value
            let expires = kv("expires=");
            let now = match id.as_str() {
                "c01" | "c03" | "c05" | "c06" | "c14" => expires.parse::<i64>().unwrap() - 3600,
                _ => 0,
            };
            rows.push(Row { id, url, setup, queued, expect, now });
        }
        rows
    }

    #[test]
    fn link_tree_matches_kotlin_corpus() {
        let rows = load_rows();
        assert_eq!(rows.len(), 20);
        for row in rows {
            let mut prefs = AuthPrefsState::fresh();
            (row.setup)(&mut prefs);
            let mut canned = Canned { queued: row.queued.into_iter().collect(), requests: 0 };
            let mut transport = canned.transport();
            let outcome = complete_email_link(
                &mut prefs,
                &row.url,
                "https://supabase.example.co/auth/v1",
                row.now,
                &mut transport,
            );
            drop(transport);
            let get = |k: &str| -> String {
                row.expect
                    .iter()
                    .find(|(key, _)| key == k)
                    .map(|(_, v)| v.clone())
                    .unwrap_or_default()
            };
            let (cls, msg, uid) = match &outcome {
                AuthOutcome::Success { user, message } => {
                    ("success", message.clone(), user.id.clone())
                }
                AuthOutcome::Error(e) => ("error", e.clone(), String::new()),
            };
            assert_eq!(cls, get("result"), "{} class", row.id);
            assert_eq!(msg, get("msg"), "{} msg", row.id);
            assert_eq!(uid, get("uid"), "{} uid", row.id);
            let opt = |v: &Option<String>| v.clone().unwrap_or_else(|| "null".to_string());
            assert_eq!(opt(&prefs.access_token), get("access"), "{} access", row.id);
            assert_eq!(opt(&prefs.refresh_token), get("refresh"), "{} refresh", row.id);
            assert_eq!(opt(&prefs.account_mode), get("mode"), "{} mode", row.id);
            assert_eq!(opt(&prefs.user_id), get("uidPref"), "{} uidPref", row.id);
            assert_eq!(opt(&prefs.user_email), get("emailPref"), "{} emailPref", row.id);
            assert_eq!(
                prefs.session_expires_at_seconds.to_string(),
                get("expires"),
                "{} expires",
                row.id
            );
            assert_eq!(prefs.needs_password_setup.to_string(), get("pwSetup"), "{} pwSetup", row.id);
            assert_eq!(
                prefs.is_pending_guest_email_link.to_string(),
                get("guestPending"),
                "{} guestPending",
                row.id
            );
            assert_eq!(opt(&prefs.pending_email), get("pendingEmail"), "{} pendingEmail", row.id);
            assert_eq!(opt(&prefs.pending_auth_action), get("action"), "{} action", row.id);
            assert_eq!(opt(&prefs.google_pkce_verifier), get("verifier"), "{} verifier", row.id);
            assert_eq!(canned.requests.to_string(), get("reqs"), "{} reqs", row.id);
        }
    }
}
