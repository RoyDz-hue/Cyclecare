//! Rust ports of SupabaseAuthService's interactive flows (31b88f5):
//! signUp, signIn, sendMagicLink, sendPasswordReset, continueAsGuest,
//! setPassword, signOut, linkGuestAccount.
//!
//! Corpus: fixtures/oracle/auth/results_flows.tsv — 22 rows executed on the
//! JVM against the VERBATIM service plus the REAL AuthPreferences class
//! (unitTest seam: real getter/setter/generation logic over an in-memory
//! store; NOT encryption evidence). Rows lock result class + message + user,
//! session writes, pending state, generation, request count, and the exact
//! request URL + body per flow.
//!
//! SEAM BOUNDARY (important): the Kotlin probe's SupabaseSessionManager stub
//! returns the raw stored access token. Production validAccessToken is a
//! refresh state machine (expiry > now+90, session-consistency check,
//! refresh transport) living in SupabaseSessionManager — a separate port
//! target (partially in session_token.rs). So the f-rows lock THIS module's
//! branch selection GIVEN a token-validity verdict, not the verdict itself.
//! The Rust port mirrors that: `valid_access_token` is an injected seam; the
//! production adapter must wire the real session-manager logic, never the
//! raw-token stub.
//!
//! deviceId generation (UUID) and persistence are host glue; the port takes
//! an injected id provider and mirrors the get-or-persist shape.

use crate::auth_errors::{for_response, AuthOperation};
use crate::auth_link::{
    read_user, save_session, AuthOutcome, AuthPrefsState, AuthUser, Transport, AUTH_ACTION_GUEST_LINK, AUTH_ACTION_MAGIC_LINK,
    AUTH_ACTION_PASSWORD_RESET, AUTH_ACTION_SIGN_UP,
};
use crate::kotlin_text::kotlin_trim;
use crate::pkce::begin_pkce_flow;
use crate::sync_codec::{json_value_to_string, JsonParser, JsonValue};

pub const AUTH_REDIRECT_URL: &str = "cyclecare://auth/callback";
/// URLEncoder.encode(AUTH_REDIRECT_URL, UTF-8) — constant.
pub const ENCODED_REDIRECT: &str = "cyclecare%3A%2F%2Fauth%2Fcallback";

const MSG_SECURE_UNAVAILABLE: &str = "Secure credential storage is unavailable on this device. Sign-in, cloud sync, and app lock are disabled until device security is restored.";
const MSG_SIGNUP_VALIDATE: &str = "Enter a valid email and a password with at least 8 characters.";
const MSG_SIGNUP_ALREADY: &str = "You are already signed in. Sign out before creating a different account.";
const MSG_SIGNUP_GUEST_REFRESH: &str =
    "Your guest session could not be refreshed. Reconnect and retry to preserve this device's data.";
const MSG_SIGNUP_CONNECT: &str = "We couldn't create your account right now. Check your connection and try again.";
const MSG_SIGNIN_GUEST_REFRESH: &str = "Your existing guest session could not be refreshed. Reconnect to preserve this device's cloud data before switching accounts.";
const MSG_SIGNIN_GUEST_PRESENT: &str = "This device has a guest account. Use Create Account or Continue with Google to keep its progress, or sign out before switching accounts.";
const MSG_SIGNIN_ALREADY: &str = "You are already signed in. Sign out before switching accounts.";
const MSG_SIGNIN_UNCONFIRMED: &str = "Please verify your email using the CycleCare link before signing in.";
const MSG_SIGNIN_CONNECT: &str = "Unable to connect. Please check your network connection.";
const MSG_MAGIC_VALIDATE: &str = "Enter your email address first.";
const MSG_MAGIC_ALREADY: &str = "You are already signed in. Sign out before requesting another sign-in link.";
const MSG_MAGIC_GUEST_REFRESH: &str =
    "Your guest session needs to reconnect before requesting a sign-in link.";
const MSG_RESET_VALIDATE: &str = "Enter a valid email address.";
const MSG_GUEST_EMAIL_PRESENT: &str =
    "This device is signed in to an email account. Sign out before starting a separate guest account.";
const MSG_GUEST_REFRESH_FAILED: &str = "Your existing guest session could not be refreshed. Reconnect to preserve this device's cloud data before continuing.";
const MSG_GUEST_CONNECT: &str =
    "A secure guest session needs an internet connection. Please retry when online.";
const MSG_GUEST_READY: &str = "Your private guest session is ready.";
const MSG_LINK_EXPIRED: &str = "Your guest session expired. Continue as guest again, then link your email.";
const MSG_LINK_SENT: &str = "A verification link was sent to %EMAIL%. Open it on this device to attach this guest data to your account; you will not enter until verification completes.";
const MSG_SETPW_SHORT: &str = "Use a password with at least 8 characters.";
const MSG_SETPW_EXPIRED: &str = "Your verified session has expired. Sign in again to set a password.";
const MSG_SETPW_DONE: &str = "Your password is set.";
const MSG_SETPW_CONNECT: &str = "Unable to connect. Please try again.";

/// Kotlin String.lowercase(): Unicode full case mapping (Rust matches;
/// differs from the SIMPLE mapping used by equals-ignore-case).
fn kotlin_lowercase(s: &str) -> String {
    s.chars().flat_map(|c| c.to_lowercase()).collect()
}

/// Kotlin String.length on the JVM counts UTF-16 code units.
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn normalize_email(email: &str) -> String {
    kotlin_lowercase(kotlin_trim(email))
}

/// Injected seams mirroring the production collaborators.
/// Token-verdict seam: what SupabaseSessionManager.validAccessToken answers.
pub enum TokenVerdict<'a> {
    /// Test seam: the raw stored access token (the probe stub the flow corpus
    /// was locked with). NEVER wire this in production: it skips the expiry,
    /// session-consistency and refresh semantics.
    /// TEST-ONLY: compiled out of non-test builds by #[cfg(test)], so no FFI
    /// or production caller can select it — the variant does not exist there.
    #[cfg(test)]
    StubRawToken,
    /// Production verdicts via the executed session-manager port
    /// (auth_session::resolve_valid_access_token): expiry > now+90,
    /// session-consistency check, refresh commit on rotation.
    Production {
        refresh_transport: &'a dyn Fn(&str) -> Result<crate::session_token::RefreshResponse, ()>,
    },
}

pub struct FlowSeams<'a> {
    pub token_verdict: TokenVerdict<'a>,
    pub now_seconds: i64,
    /// 32 random bytes for PKCE (SecureRandom in production).
    pub rng32: &'a dyn Fn() -> [u8; 32],
    /// UUID generation for a fresh device id (host glue in production).
    pub new_device_id: &'a dyn Fn() -> String,
}

impl FlowSeams<'_> {
    pub(crate) fn resolve_token(&self, prefs: &mut AuthPrefsState) -> Option<String> {
        match &self.token_verdict {
            #[cfg(test)]
            TokenVerdict::StubRawToken => prefs.access_token.clone(),
            TokenVerdict::Production { refresh_transport } => {
                crate::auth_session::resolve_valid_access_token(
                    prefs,
                    self.now_seconds,
                    *refresh_transport,
                )
            }
        }
    }
}

fn strv(s: &str) -> JsonValue {
    JsonValue::Str(s.to_string())
}

fn obj(entries: Vec<(&str, JsonValue)>) -> JsonValue {
    JsonValue::Object(entries.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
}

fn blank(s: &Option<String>) -> bool {
    s.as_deref().map(|v| v.is_empty()).unwrap_or(true)
}

fn is_anon(prefs: &AuthPrefsState) -> bool {
    prefs.account_mode.as_deref() == Some("ANONYMOUS")
}

/// deviceId: get-or-generate-and-persist (AuthPreferences.kt:342-350).
fn device_id(prefs: &mut AuthPrefsState, seams: &FlowSeams) -> String {
    if let Some(id) = prefs.device_id.as_ref().filter(|v| !v.is_empty()) {
        return id.clone();
    }
    let id = (seams.new_device_id)();
    prefs.device_id = Some(id.clone());
    id
}

pub fn sign_out(prefs: &mut AuthPrefsState) {
    prefs.clear_session();
}

/// Verbatim port of signUp.
pub fn sign_up(
    prefs: &mut AuthPrefsState,
    email: &str,
    password: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let normalized_email = normalize_email(email);
    if normalized_email.is_empty() || !normalized_email.contains('@') || utf16_len(password) < 8 {
        return AuthOutcome::Error(MSG_SIGNUP_VALIDATE.to_string());
    }
    // try {
    let existing_token = seams.resolve_token(prefs);
    if !blank(&existing_token) {
        if is_anon(prefs) {
            return link_guest_account(prefs, &normalized_email, auth_api_url, seams, transport);
        }
        return AuthOutcome::Error(MSG_SIGNUP_ALREADY.to_string());
    }
    if is_anon(prefs) && !blank(&prefs.user_id) && !blank(&prefs.refresh_token) {
        return AuthOutcome::Error(MSG_SIGNUP_GUEST_REFRESH.to_string());
    }
    let body = obj(vec![
        ("email", strv(&normalized_email)),
        ("password", strv(password)),
        (
            "data",
            obj(vec![
                ("device_id", strv(&device_id(prefs, seams))),
                ("account_mode", strv("EMAIL")),
                ("app_name", strv("CycleCare")),
            ]),
        ),
    ]);
    let body = add_pkce_fields(prefs, body, AUTH_ACTION_SIGN_UP, seams);
    let url = format!(
        "{}/signup?redirect_to={ENCODED_REDIRECT}",
        auth_api_url.trim_end_matches('/')
    );
    let body_text = json_value_to_string(&body);
    let response = match transport("POST", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SIGNUP_CONNECT.to_string()),
    };
    if !response.successful {
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::SignUp));
    }
    let json = match JsonParser::new(&response.body).parse() {
        Ok(v) => v,
        Err(_) => return AuthOutcome::Error(MSG_SIGNUP_CONNECT.to_string()),
    };
    let user = opt_object_of(&json, "user").cloned().unwrap_or_else(|| json.clone());
    let confirmed = crate::auth_link::email_is_confirmed(&user);
    let access_token = crate::sync_codec::aosp_opt_string(&json, "access_token", "");
    if !access_token.is_empty() && confirmed {
        prefs.clear_pending_auth_flow(true);
        save_session(prefs, &json, "EMAIL", seams.now_seconds);
        return AuthOutcome::Success {
            user: read_user(&user, Some(normalized_email.as_str())),
            message: "Your verified CycleCare account is ready.".to_string(),
        };
    }
    // Never retain an unverified session, even if the project issues one.
    prefs.pending_email = Some(normalized_email.clone());
    prefs.pending_auth_action = Some(AUTH_ACTION_SIGN_UP.to_string());
    AuthOutcome::VerificationRequired(format!(
        "Check {normalized_email} for your CycleCare verification link. The app will open automatically after you verify; you can enter only then."
    ))
}

/// Verbatim port of signIn.
pub fn sign_in(
    prefs: &mut AuthPrefsState,
    email: &str,
    password: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let normalized_email = normalize_email(email);
    let existing_token = seams.resolve_token(prefs);
    if blank(&existing_token) && is_anon(prefs) && !blank(&prefs.user_id) && !blank(&prefs.refresh_token) {
        return AuthOutcome::Error(MSG_SIGNIN_GUEST_REFRESH.to_string());
    }
    if !blank(&existing_token) && !blank(&prefs.user_id) {
        return AuthOutcome::Error(
            if is_anon(prefs) { MSG_SIGNIN_GUEST_PRESENT } else { MSG_SIGNIN_ALREADY }.to_string(),
        );
    }
    let body = obj(vec![("email", strv(&normalized_email)), ("password", strv(password))]);
    let url = format!("{}/token?grant_type=password", auth_api_url.trim_end_matches('/'));
    let body_text = json_value_to_string(&body);
    let response = match transport("POST", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SIGNIN_CONNECT.to_string()),
    };
    // The interpretation is the SHARED wiring seam (auth_link::
    // interpret_sign_in_response, NEW IMPLEMENTATION 2026-11-13): the JNI
    // wiring and this port run ONE implementation. DEVIATION from the
    // previous port-internal code on an edge the f-rows do NOT lock: a
    // 2xx body that parses as a NON-OBJECT now yields the connect
    // catch-all message (what the Kotlin source's JSONObject(body) throw
    // produces), not a confirmation failure.
    match crate::auth_link::interpret_sign_in_response(response.successful, &response.body, &normalized_email) {
        crate::auth_link::SignInInterpretation::Error(m) => AuthOutcome::Error(m),
        crate::auth_link::SignInInterpretation::VerificationRequired(m) => {
            prefs.clear_session();
            AuthOutcome::Error(m)
        }
        crate::auth_link::SignInInterpretation::Success { user, message, session } => {
            crate::auth_link::apply_session_fields(prefs, &session, seams.now_seconds);
            AuthOutcome::Success { user, message }
        }
    }
}

/// Verbatim port of sendMagicLink.
pub fn send_magic_link(
    prefs: &mut AuthPrefsState,
    email: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let normalized_email = normalize_email(email);
    if normalized_email.is_empty() || !normalized_email.contains('@') {
        return AuthOutcome::Error(MSG_MAGIC_VALIDATE.to_string());
    }
    let existing_token = seams.resolve_token(prefs);
    if !blank(&existing_token) {
        if is_anon(prefs) {
            return link_guest_account(prefs, &normalized_email, auth_api_url, seams, transport);
        }
        return AuthOutcome::Error(MSG_MAGIC_ALREADY.to_string());
    }
    if is_anon(prefs) && !blank(&prefs.user_id) && !blank(&prefs.refresh_token) {
        return AuthOutcome::Error(MSG_MAGIC_GUEST_REFRESH.to_string());
    }
    let mut body = obj(vec![
        ("email", strv(&normalized_email)),
        ("create_user", JsonValue::Bool(false)),
    ]);
    body = add_pkce_fields(prefs, body, AUTH_ACTION_MAGIC_LINK, seams);
    let url = format!(
        "{}/otp?redirect_to={ENCODED_REDIRECT}",
        auth_api_url.trim_end_matches('/')
    );
    let body_text = json_value_to_string(&body);
    let response = match transport("POST", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SIGNIN_CONNECT.to_string()),
    };
    if !response.successful {
        prefs.clear_pending_auth_flow(true);
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::MagicLink));
    }
    prefs.pending_email = Some(normalized_email.clone());
    prefs.pending_auth_action = Some(AUTH_ACTION_MAGIC_LINK.to_string());
    AuthOutcome::VerificationRequired(format!(
        "A secure CycleCare sign-in link was sent to {normalized_email}. Open it on this device to continue."
    ))
}

/// Verbatim port of sendPasswordReset.
pub fn send_password_reset(
    prefs: &mut AuthPrefsState,
    email: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let normalized_email = normalize_email(email);
    if normalized_email.is_empty() || !normalized_email.contains('@') {
        return AuthOutcome::Error(MSG_RESET_VALIDATE.to_string());
    }
    let body = obj(vec![("email", strv(&normalized_email))]);
    let body = add_pkce_fields(prefs, body, AUTH_ACTION_PASSWORD_RESET, seams);
    let url = format!(
        "{}/recover?redirect_to={ENCODED_REDIRECT}",
        auth_api_url.trim_end_matches('/')
    );
    let body_text = json_value_to_string(&body);
    let response = match transport("POST", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SIGNIN_CONNECT.to_string()),
    };
    if response.successful {
        prefs.pending_auth_action = Some(AUTH_ACTION_PASSWORD_RESET.to_string());
        prefs.pending_email = Some(normalized_email.clone());
        AuthOutcome::VerificationRequired(format!(
            "Password reset instructions were sent to {normalized_email}. Open the CycleCare link on this device."
        ))
    } else {
        prefs.clear_pending_auth_flow(true);
        AuthOutcome::Error(for_response(&response.body, AuthOperation::PasswordReset))
    }
}

/// Verbatim port of continueAsGuest.
pub fn continue_as_guest(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let current = seams.resolve_token(prefs);
    if !is_anon(prefs) {
        return AuthOutcome::Error(MSG_GUEST_EMAIL_PRESENT.to_string());
    }
    if !blank(&current) && !blank(&prefs.user_id) {
        return AuthOutcome::Success {
            user: AuthUser {
                id: prefs.user_id.clone().unwrap_or_default(),
                email: None,
                email_confirmed_at: None,
                is_anonymous: true,
            },
            message: MSG_GUEST_READY.to_string(),
        };
    }
    if is_anon(prefs) && !blank(&prefs.user_id) && !blank(&prefs.refresh_token) {
        return AuthOutcome::Error(MSG_GUEST_REFRESH_FAILED.to_string());
    }
    let body = obj(vec![(
        "data",
        obj(vec![
            ("device_id", strv(&device_id(prefs, seams))),
            ("account_mode", strv("ANONYMOUS")),
            ("app_name", strv("CycleCare")),
        ]),
    )]);
    let url = format!("{}/signup", auth_api_url.trim_end_matches('/'));
    let body_text = json_value_to_string(&body);
    let response = match transport("POST", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_GUEST_CONNECT.to_string()),
    };
    if !response.successful {
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::Guest));
    }
    let json = match JsonParser::new(&response.body).parse() {
        Ok(v) => v,
        Err(_) => return AuthOutcome::Error(MSG_GUEST_CONNECT.to_string()),
    };
    let user = opt_object_of(&json, "user").cloned().unwrap_or_else(|| json.clone());
    let user_id = crate::sync_codec::aosp_opt_string(&user, "id", "");
    let access_token = crate::sync_codec::aosp_opt_string(&json, "access_token", "");
    if user_id.is_empty() || access_token.is_empty() {
        return AuthOutcome::Error(crate::auth_errors::fallback(AuthOperation::Guest).to_string());
    }
    save_session(prefs, &json, "ANONYMOUS", seams.now_seconds);
    prefs.user_email = None;
    AuthOutcome::Success {
        user: AuthUser { id: user_id, email: None, email_confirmed_at: None, is_anonymous: true },
        message: MSG_GUEST_READY.to_string(),
    }
}

/// Verbatim port of setPassword.
pub fn set_password(
    prefs: &mut AuthPrefsState,
    password: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    if utf16_len(password) < 8 {
        return AuthOutcome::Error(MSG_SETPW_SHORT.to_string());
    }
    let _token = match seams.resolve_token(prefs) {
        Some(t) if !t.is_empty() => t,
        _ => return AuthOutcome::Error(MSG_SETPW_EXPIRED.to_string()),
    };
    let body = obj(vec![("password", strv(password))]);
    let url = format!("{}/user", auth_api_url.trim_end_matches('/'));
    let body_text = json_value_to_string(&body);
    let response = match transport("PUT", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SETPW_CONNECT.to_string()),
    };
    if !response.successful {
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::SetPassword));
    }
    prefs.needs_password_setup = false;
    let user = JsonParser::new(&response.body).parse().unwrap_or(JsonValue::Object(vec![]));
    AuthOutcome::Success {
        user: read_user(&user, Some(prefs.user_email.clone().unwrap_or_default().as_str())),
        message: MSG_SETPW_DONE.to_string(),
    }
}

/// Verbatim port of linkGuestAccount.
fn link_guest_account(
    prefs: &mut AuthPrefsState,
    email: &str,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> AuthOutcome {
    let _token = match seams.resolve_token(prefs) {
        Some(t) if !t.is_empty() => t,
        _ => return AuthOutcome::Error(MSG_LINK_EXPIRED.to_string()),
    };
    let body = obj(vec![("email", strv(email))]);
    let body = add_pkce_fields(prefs, body, AUTH_ACTION_GUEST_LINK, seams);
    let url = format!(
        "{}/user?redirect_to={ENCODED_REDIRECT}",
        auth_api_url.trim_end_matches('/')
    );
    let body_text = json_value_to_string(&body);
    let response = match transport("PUT", &url, Some(&body_text)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_SIGNIN_CONNECT.to_string()),
    };
    if !response.successful {
        prefs.clear_pending_auth_flow(false);
        return AuthOutcome::Error(for_response(&response.body, AuthOperation::AccountLink));
    }
    prefs.pending_email = Some(email.to_string());
    prefs.is_pending_guest_email_link = true;
    prefs.pending_auth_action = Some(AUTH_ACTION_GUEST_LINK.to_string());
    AuthOutcome::VerificationRequired(MSG_LINK_SENT.replace("%EMAIL%", email))
}

/// addPkceFields: appends code_challenge + code_challenge_method at the END
/// (insertion order matters for the recorded request bodies).
fn add_pkce_fields(
    prefs: &mut AuthPrefsState,
    mut body: JsonValue,
    action: &str,
    seams: &FlowSeams,
) -> JsonValue {
    let challenge = begin_pkce_flow((seams.rng32)(), action, prefs);
    if let JsonValue::Object(entries) = &mut body {
        entries.push(("code_challenge".to_string(), strv(&challenge)));
        entries.push(("code_challenge_method".to_string(), strv("s256")));
    }
    body
}

fn opt_object_of<'a>(json: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match json {
        JsonValue::Object(entries) => entries
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v)
            .filter(|v| matches!(v, JsonValue::Object(_))),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth_link::HttpResponse;
    use std::path::PathBuf;

    const API: &str = "https://supabase.example.co/auth/v1";

    struct Canned {
        queued: std::collections::VecDeque<HttpResponse>,
        requests: Vec<String>,
    }

    fn resp(ok: bool, body: &str) -> HttpResponse {
        HttpResponse { successful: ok, body: body.to_string(), header_location: None }
    }

    /// Replace device_id and code_challenge values (random in the recorded
    /// corpus, deterministically injected here) with a fixed marker so the
    /// rest of the request body compares exactly.
    fn normalize_body(json_text: &str) -> String {
        let mut v = JsonParser::new(json_text).parse().expect("body json");
        fn scrub(v: &mut JsonValue) {
            if let JsonValue::Object(entries) = v {
                for (k, val) in entries.iter_mut() {
                    if k == "device_id" || k == "code_challenge" {
                        *val = JsonValue::Str("X".to_string());
                    } else {
                        scrub(val);
                    }
                }
            }
        }
        scrub(&mut v);
        json_value_to_string(&v)
    }

    struct Row {
        id: String,
        setup: fn(&mut AuthPrefsState),
        queued: Vec<HttpResponse>,
        expect: Vec<(String, String)>,
        describe: String,
        reqs: String,
    }

    fn load_rows() -> Vec<Row> {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("fixtures/oracle/auth/results_flows.tsv");
        let text = std::fs::read_to_string(p).unwrap();
        let mut rows = Vec::new();
        for line in text.lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.len() < 3 || cols[1] != "flow" {
                continue;
            }
            let id = cols[0].to_string();
            let kv = |prefix: &str| -> String {
                cols.iter().find_map(|c| c.strip_prefix(prefix)).unwrap_or("").to_string()
            };
            let expect: Vec<(String, String)> = cols[3..]
                .iter()
                .filter_map(|c| c.split_once('='))
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect();
            let setup: fn(&mut AuthPrefsState) = match id.as_str() {
                "f09" => |p: &mut AuthPrefsState| { p.set_user_id(Some("anon-existing".into())); },
                "f10" | "f11" => |p: &mut AuthPrefsState| {
                    p.access_token = Some("T1".into());
                    p.set_user_id(Some("u1".into()));
                },
                "f12" | "f15" | "f16" | "f19" => |p: &mut AuthPrefsState| {
                    p.access_token = Some("TOK".into());
                    p.set_user_id(Some("anon-1".into()));
                },
                "f13" | "f17" => |p: &mut AuthPrefsState| {
                    p.set_user_id(Some("anon-1".into()));
                    p.refresh_token = Some("R1".into());
                },
                "f14" | "f20" => |p: &mut AuthPrefsState| {
                    p.access_token = Some("TOK".into());
                    p.set_user_id(Some("u9".into()));
                    p.account_mode = Some("EMAIL".into());
                },
                "f18" => |p: &mut AuthPrefsState| { p.account_mode = Some("EMAIL".into()); },
                _ => |_| {},
            };
            let queued = match id.as_str() {
                "f01" | "f04" => vec![resp(true, r#"{"access_token":"T1","refresh_token":"R2","expires_in":3600,"user":{"id":"u1","email":"e@x.y","email_confirmed_at":"2026-01-01","is_anonymous":false}}"#)],
                "f02" => vec![resp(false, r#"{"code":400,"msg":"User already registered"}"#)],
                "f03" => vec![resp(true, r#"{"access_token":"T1","refresh_token":"R2","expires_in":3600,"user":{"id":"u1","email":"e@x.y","is_anonymous":false}}"#)],
                "f05" => vec![resp(false, r#"{"code":400,"msg":"Invalid login credentials"}"#)],
                "f06" | "f07" => vec![resp(true, "{}")],
                "f08" => vec![resp(true, r#"{"access_token":"G1","refresh_token":"G2","expires_in":3600,"user":{"id":"anon1","is_anonymous":true}}"#)],
                "f10" => vec![resp(true, r#"{"id":"u1","email":"e@x.y"}"#)],
                "f11" | "f19" => vec![resp(false, r#"{"code":400,"msg":"rate limit"}"#)],
                "f16" => vec![resp(true, r#"{"id":"anon-1"}"#)],
                _ => vec![],
            };
            let reqs = kv("reqs=");
            rows.push(Row { id, setup, queued, expect, describe: cols[2].to_string(), reqs });
        }
        rows
    }

    /// Integration: the production token verdict (executed session-manager
    /// port) drives flow branches — live token => "already signed in" with no
    /// network; expired token => refresh commits, then the flow proceeds.
    #[test]
    fn production_verdict_drives_sign_in_branch() {
        use crate::auth_link::HttpResponse;
        use crate::pkce::b64url_no_pad;
        let jwt = |sub: &str, exp: i64| {
            let h = b64url_no_pad(br#"{"alg":"none"}"#);
            let p = b64url_no_pad(format!(r#"{{"sub":"{sub}","exp":{exp}}}"#).as_bytes());
            format!("{h}.{p}.ss")
        };
        // Live token: no refresh, no password grant, immediate guard error.
        let mut prefs = AuthPrefsState::fresh();
        prefs.access_token = Some(jwt("u9", 4_000_000_000));
        prefs.refresh_token = Some("R1".into());
        prefs.set_user_id(Some("u9".into()));
        prefs.session_expires_at_seconds = 4_000_000_000;
        prefs.account_mode = Some("EMAIL".into());
        let refresh = |_: &str| -> Result<crate::session_token::RefreshResponse, ()> {
            panic!("no refresh expected")
        };
        let seams = FlowSeams {
            token_verdict: TokenVerdict::Production { refresh_transport: &refresh },
            now_seconds: 1_000,
            rng32: &|| [0x42; 32],
            new_device_id: &|| "dev".to_string(),
        };
        let mut transport = |_: &str, _: &str, _: Option<&str>| -> Result<HttpResponse, String> {
            panic!("no network expected with a live token")
        };
        let outcome = sign_in(&mut prefs, "e@x.y", "password123", API, &seams, &mut transport);
        assert!(matches!(outcome, AuthOutcome::Error(ref m) if m == "You are already signed in. Sign out before switching accounts."));

        // Expired token: refresh rotates and commits, then the password grant runs.
        let mut prefs = AuthPrefsState::fresh();
        prefs.access_token = Some(jwt("u9", 100));
        prefs.refresh_token = Some("R1".into());
        prefs.set_user_id(Some("u9".into()));
        prefs.session_expires_at_seconds = 100;
        prefs.account_mode = Some("EMAIL".into());
        let renewed = jwt("u9", 4_000_000_000);
        let refresh_body = format!(
            r#"{{"access_token":"{renewed}","refresh_token":"R2","expires_in":3600,"user":{{"id":"u9","email":"old@x.y","is_anonymous":false}}}}"#
        );
        let refresh = move |_: &str| -> Result<crate::session_token::RefreshResponse, ()> {
            Ok(crate::session_token::RefreshResponse { code: 200, body: Some(refresh_body.clone()) })
        };
        let seams = FlowSeams {
            token_verdict: TokenVerdict::Production { refresh_transport: &refresh },
            now_seconds: 1_000,
            rng32: &|| [0x42; 32],
            new_device_id: &|| "dev".to_string(),
        };
        let mut calls = 0;
        let mut transport = |_: &str, _: &str, _: Option<&str>| -> Result<HttpResponse, String> {
            calls += 1;
            Ok(HttpResponse { successful: true, body: r#"{"access_token":"T","refresh_token":"R3","expires_in":3600,"user":{"id":"u9","email":"e@x.y","email_confirmed_at":"x","is_anonymous":false}}"#.to_string(), header_location: None })
        };
        // With a renewed valid token and an EMAIL account, sign_in hits the
        // already-signed-in guard — proving the refresh committed first.
        let outcome = sign_in(&mut prefs, "e@x.y", "password123", API, &seams, &mut transport);
        assert!(matches!(outcome, AuthOutcome::Error(ref m) if m == "You are already signed in. Sign out before switching accounts."));
        assert_eq!(calls, 0, "guard fires before the grant request");
        assert_eq!(prefs.refresh_token.as_deref(), Some("R2"), "refresh rotation committed");
    }

    #[test]
    fn flows_match_kotlin_corpus() {
        let rows = load_rows();
        assert_eq!(rows.len(), 22);
        for row in rows {
            let mut prefs = AuthPrefsState::fresh();
            (row.setup)(&mut prefs);
            let mut canned = Canned { queued: row.queued.into_iter().collect(), requests: vec![] };
            let seams = FlowSeams {
                token_verdict: TokenVerdict::StubRawToken,
                now_seconds: 0,
                rng32: &|| [0x42u8; 32],
                new_device_id: &|| "dev-fixed".to_string(),
            };
            let outcome = {
                let mut transport = |method: &str, url: &str, body: Option<&str>| -> Result<HttpResponse, String> {
                    canned.requests.push(format!("{}>>{}", url, body.unwrap_or("-")));
                    let _ = method;
                    canned.queued.pop_front().ok_or_else(|| "probe: no response queued".to_string())
                };
                match row.id.as_str() {
                    "f01" => sign_up(&mut prefs, "E@x.y ", "password123", API, &seams, &mut transport),
                    "f02" | "f03" | "f14" | "f17" => sign_up(&mut prefs, "e@x.y", "password123", API, &seams, &mut transport),
                    "f04" => sign_in(&mut prefs, "e@x.y", "password123", API, &seams, &mut transport),
                    "f05" | "f15" => sign_in(&mut prefs, "e@x.y", if row.id == "f05" { "wrong" } else { "password123" }, API, &seams, &mut transport),
                    "f06" | "f16" | "f19" | "f20" | "f22" => {
                        send_magic_link(&mut prefs, if row.id == "f22" { "nope" } else { "e@x.y" }, API, &seams, &mut transport)
                    }
                    "f07" => send_password_reset(&mut prefs, "e@x.y", API, &seams, &mut transport),
                    "f08" | "f09" | "f12" | "f13" | "f18" => {
                        continue_as_guest(&mut prefs, API, &seams, &mut transport)
                    }
                    "f10" | "f11" => set_password(&mut prefs, "newpassword1", API, &seams, &mut transport),
                    "f21" => sign_up(&mut prefs, "bad-email", "short", API, &seams, &mut transport),
                    other => panic!("unmapped row {other}"),
                }
            };
            let (cls, msg, uid, anon, email) = match &outcome {
                AuthOutcome::Success { user, message } => (
                    "success",
                    message.clone(),
                    user.id.clone(),
                    user.is_anonymous.to_string(),
                    user.email.clone().unwrap_or_else(|| "null".to_string()),
                ),
                AuthOutcome::VerificationRequired(m) => {
                    ("verify", m.clone(), String::new(), String::new(), String::new())
                }
                AuthOutcome::Error(e) => {
                    ("error", e.clone(), String::new(), String::new(), String::new())
                }
            };
            let d: Vec<&str> = row.describe.split('|').collect();
            assert_eq!(cls, d[0], "{} class", row.id);
            assert_eq!(msg, d[1], "{} msg", row.id);
            if d[0] == "success" {
                assert_eq!(uid, d[2], "{} uid", row.id);
                assert_eq!(anon, d[3], "{} anon", row.id);
                assert_eq!(email, d[4], "{} email", row.id);
            }
            let get = |k: &str| -> String {
                row.expect.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone()).unwrap_or_default()
            };
            assert_eq!(prefs.account_mode.as_deref().unwrap_or("null"), get("mode"), "{} mode", row.id);
            assert_eq!(prefs.user_id.as_deref().unwrap_or("null"), get("uid"), "{} uidPref", row.id);
            assert_eq!(prefs.access_token.as_deref().unwrap_or("null"), get("access"), "{} access", row.id);
            assert_eq!(prefs.refresh_token.as_deref().unwrap_or("null"), get("refresh"), "{} refresh", row.id);
            assert_eq!(prefs.pending_email.as_deref().unwrap_or("null"), get("pendingEmail"), "{} pendingEmail", row.id);
            assert_eq!(prefs.is_pending_guest_email_link.to_string(), get("guest"), "{} guest", row.id);
            assert_eq!(prefs.pending_auth_action.as_deref().unwrap_or("null"), get("action"), "{} action", row.id);
            assert_eq!(prefs.needs_password_setup.to_string(), get("pwSetup"), "{} pwSetup", row.id);
            assert_eq!(prefs.session_generation.to_string(), get("gen"), "{} gen", row.id);
            let expect_reqs: Vec<String> = if row.reqs.is_empty() {
                vec![]
            } else {
                row.reqs.split(";;").map(|r| {
                    let mut parts = r.splitn(2, ">>");
                    let url = parts.next().unwrap_or("");
                    let body = parts.next().unwrap_or("-");
                    if body == "-" { format!("{url}>>-") } else { format!("{url}>>{}", normalize_body(body)) }
                }).collect()
            };
            let got_reqs: Vec<String> = canned.requests.iter().map(|r| {
                let mut parts = r.splitn(2, ">>");
                let url = parts.next().unwrap_or("");
                let body = parts.next().unwrap_or("-");
                if body == "-" { format!("{url}>>-") } else { format!("{url}>>{}", normalize_body(body)) }
            }).collect();
            assert_eq!(got_reqs, expect_reqs, "{} requests", row.id);
        }
    }
}
