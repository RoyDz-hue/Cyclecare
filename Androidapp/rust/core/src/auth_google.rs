//! Rust ports of SupabaseAuthService's Google URL builders + isCycleCareAdmin
//! (31b88f5): createGoogleSignInUrl, resolveGoogleAuthUrl,
//! createGoogleLinkUrl, isCycleCareAdmin.
//!
//! Corpus: fixtures/oracle/auth/results_google.tsv — 10 rows (g01-g10)
//! executed against the verbatim service + real AuthPreferences on the JVM.
//! createGoogleSignInUrl's guards THROW IllegalStateException (not
//! AuthResult); the port returns Result<_, String> where Err is the exact
//! exception message. Transport is injected; the Authorization Bearer header
//! uses the injected token-verdict seam exactly like auth_flows.

use crate::auth_errors::{fallback, for_response, AuthOperation};
use crate::auth_flows::{FlowSeams, ENCODED_REDIRECT};
use crate::auth_link::{AuthPrefsState, Transport, AUTH_ACTION_GOOGLE, AUTH_ACTION_GOOGLE_LINK};
use crate::kotlin_text::{kotlin_equals_ignore_case, kotlin_is_blank, kotlin_trim};
use crate::pkce::begin_pkce_flow;
use crate::sync_codec::{aosp_opt_string, aosp_type_mismatch, JsonParser, JsonValue};

const MSG_SIGNIN_UNAVAILABLE: &str = "Secure credential storage is unavailable. Sign-in is disabled until device security is restored.";
const MSG_LINK_UNAVAILABLE: &str = "Secure credential storage is unavailable. Linking is disabled until device security is restored.";
const MSG_GOOGLE_SIGNOUT_FIRST: &str = "To preserve this device's CycleCare data, sign out before using Google sign-in. Guest account linking through Google is not enabled yet.";
const MSG_GUEST_NEEDS_REFRESH: &str = "Your guest session needs a refresh. Reconnect and try again.";

/// URLEncoder.encode(s, UTF-8): alphanumerics and .-* are kept, space becomes
/// '+', everything else %XX uppercase (UTF-8 bytes).
pub fn url_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

// ---------- wiring seams: Google auth URL surface (NEW IMPLEMENTATION,
// 2026-11-13) ----------
// NON-CLOCK decisions only. SecureRandom (PKCE) and the CLOCK-GOVERNED
// token resolution (SupabaseSessionManager.validAccessToken, with its
// possible refresh network call) stay at their ORIGINAL Kotlin execution
// points; only their OUTCOMES cross in. The verifier never crosses at all.
// Link tokens inside URLs cross OUT only as part of the assembled URL the
// Kotlin caller would build anyway (same-device JNI copy, no
// logging/persistence/egress, no zeroization - standing accounting).

/// Pre-guard verdict: the source THROWS IllegalStateException(message).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GooglePreDecision {
    Error(String),
    Proceed,
}

/// createGoogleSignInUrl's pre-guards, in source order: the secure-storage
/// check() then the signed-in guard. Both reads are side-effect-free, so
/// one seam covers both. Blank checks are Kotlin isBlank.
pub fn interpret_google_sign_in_pre(
    secure_storage_available: bool,
    user_id: Option<&str>,
) -> GooglePreDecision {
    if !secure_storage_available {
        return GooglePreDecision::Error(MSG_SIGNIN_UNAVAILABLE.to_string());
    }
    if user_id.map(|u| !kotlin_is_blank(u)).unwrap_or(false) {
        return GooglePreDecision::Error(MSG_GOOGLE_SIGNOUT_FIRST.to_string());
    }
    GooglePreDecision::Proceed
}

/// createGoogleLinkUrl's FIRST guard only (check(secure storage)). The
/// token resolution that follows in the source is CLOCK-GOVERNED with a
/// possible network refresh - a side effect - so this gate is called
/// BEFORE it and the token gate (interpret_google_link_token_pre) AFTER,
/// preserving the source's observable order.
pub fn interpret_google_link_secure_pre(secure_storage_available: bool) -> GooglePreDecision {
    if !secure_storage_available {
        return GooglePreDecision::Error(MSG_LINK_UNAVAILABLE.to_string());
    }
    GooglePreDecision::Proceed
}

/// createGoogleLinkUrl's token-presence gate (source: `?: throw`).
pub fn interpret_google_link_token_pre(token_resolved: bool) -> GooglePreDecision {
    if !token_resolved {
        return GooglePreDecision::Error(MSG_GUEST_NEEDS_REFRESH.to_string());
    }
    GooglePreDecision::Proceed
}

/// resolveGoogleAuthUrl's branch: guests (ANONYMOUS with a non-blank
/// userId) get the identity-link flow; everyone else plain sign-in.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GoogleResolveDecision {
    Link,
    SignIn,
}

pub fn interpret_google_resolve(
    account_mode: Option<&str>,
    user_id: Option<&str>,
) -> GoogleResolveDecision {
    if account_mode == Some("ANONYMOUS") && user_id.map(|u| !kotlin_is_blank(u)).unwrap_or(false) {
        GoogleResolveDecision::Link
    } else {
        GoogleResolveDecision::SignIn
    }
}

/// The authorize-URL assembly (URLEncoder.encode + concatenation). The
/// source's two shapes differ exactly this way: sign-in uses the RAW base
/// and "/authorize"; the link flow trims trailing '/' from the base, uses
/// "/user/identities/authorize" and appends "&skip_http_redirect=true".
pub fn assemble_google_authorize_url(auth_api_url: &str, challenge: &str, link_flow: bool) -> String {
    let encoded_challenge = url_encode(challenge);
    if link_flow {
        format!(
            "{}/user/identities/authorize?provider=google&redirect_to={ENCODED_REDIRECT}&code_challenge={encoded_challenge}&code_challenge_method=s256&skip_http_redirect=true",
            auth_api_url.trim_end_matches('/')
        )
    } else {
        format!(
            "{auth_api_url}/authorize?provider=google&redirect_to={ENCODED_REDIRECT}&code_challenge={encoded_challenge}&code_challenge_method=s256"
        )
    }
}

/// createGoogleLinkUrl's response interpretation. NON-CLOCK; the commit
/// (clearPendingAuthFlow via mutatePublicationIdentityIfCurrent) and the
/// throw stay at the Kotlin call site.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GoogleLinkResponseDecision {
    /// Commit clearPendingAuthFlow() (no email clear), then throw
    /// IllegalStateException(message).
    ErrorClear(String),
    /// JSONObject(body) throws in the source: NO commit; the AOSP
    /// JSONException message propagates. (The older full-port tree
    /// swallowed a parse failure into the Location-fallback branch - a
    /// corpus-unlocked divergence, fixed here.)
    ParseThrow(String),
    Url(String),
}

pub fn interpret_google_link_response(
    successful: bool,
    body: &str,
    location: Option<&str>,
) -> GoogleLinkResponseDecision {
    if !successful {
        return GoogleLinkResponseDecision::ErrorClear(
            for_response(body, AuthOperation::AccountLink).to_string(),
        );
    }
    let json = match JsonParser::new(body).parse() {
        Ok(v) => v,
        Err(msg) => return GoogleLinkResponseDecision::ParseThrow(msg),
    };
    let url_raw = match &json {
        JsonValue::Object(_) => aosp_opt_string(&json, "url", ""),
        other => return GoogleLinkResponseDecision::ParseThrow(aosp_type_mismatch(other, "JSONObject")),
    };
    // Source: optString("url").ifBlank { Location.orEmpty() }, then
    // isBlank -> clear + fallback throw. BLANK, not empty (whitespace-only
    // falls through to the header; the older tree's is_empty diverged).
    let url = if kotlin_is_blank(&url_raw) { location.unwrap_or("") } else { url_raw.as_str() };
    if kotlin_is_blank(url) {
        GoogleLinkResponseDecision::ErrorClear(fallback(AuthOperation::AccountLink).to_string())
    } else {
        GoogleLinkResponseDecision::Url(url.to_string())
    }
}

/// isCycleCareAdmin's response interpretation: `body?.trim().equals("true",
/// ignoreCase = true)` with the null-body -> false shape. Kotlin trim
/// (probe-locked whitespace set) + simple-mapping ignoreCase; for the
/// literal "true" the simple mapping is exact (t/r/u/e have no fold
/// variants beyond ASCII).
pub fn interpret_admin_response(successful: bool, body: Option<&str>) -> bool {
    successful && body.map(|b| kotlin_equals_ignore_case(kotlin_trim(b), "true")).unwrap_or(false)
}

/// Verbatim port of createGoogleSignInUrl. Err = the thrown message.
/// ONE IMPLEMENTATION (2026-11-13): guards and assembly come from the
/// wiring seams; this tree owns only the injected randomness + prefs write
/// (begin_pkce_flow at its original point, AFTER the guards).
pub fn create_google_sign_in_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
) -> Result<String, String> {
    match interpret_google_sign_in_pre(prefs.is_secure_storage_available, prefs.user_id.as_deref()) {
        GooglePreDecision::Error(m) => return Err(m),
        GooglePreDecision::Proceed => {}
    }
    let challenge = begin_pkce_flow((seams.rng32)(), AUTH_ACTION_GOOGLE, prefs);
    Ok(assemble_google_authorize_url(auth_api_url, &challenge, false))
}

/// Verbatim port of resolveGoogleAuthUrl.
pub fn resolve_google_auth_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> Result<String, String> {
    match interpret_google_resolve(prefs.account_mode.as_deref(), prefs.user_id.as_deref()) {
        GoogleResolveDecision::Link => create_google_link_url(prefs, auth_api_url, seams, transport),
        GoogleResolveDecision::SignIn => create_google_sign_in_url(prefs, auth_api_url, seams),
    }
}

/// Verbatim port of createGoogleLinkUrl.
fn create_google_link_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> Result<String, String> {
    // Source order: the secure gate, THEN the (injected, clock-governed in
    // production) token resolution, THEN the token-presence gate.
    match interpret_google_link_secure_pre(prefs.is_secure_storage_available) {
        GooglePreDecision::Error(m) => return Err(m),
        GooglePreDecision::Proceed => {}
    }
    let token = seams.resolve_token(prefs);
    match interpret_google_link_token_pre(token.is_some()) {
        GooglePreDecision::Error(m) => return Err(m),
        GooglePreDecision::Proceed => {}
    }
    let _ = token;
    let challenge = begin_pkce_flow((seams.rng32)(), AUTH_ACTION_GOOGLE_LINK, prefs);
    let url = assemble_google_authorize_url(auth_api_url, &challenge, true);
    // NOTE (tree-only): the source lets OkHttp's IOException from execute()
    // PROPAGATE; the injected Transport has no exception channel, so a
    // transport error maps to the ACCOUNT_LINK fallback here. The WIRED
    // path keeps the real throw in Kotlin; no corpus row covers this.
    let response = transport("GET", &url, None).map_err(|_| fallback(AuthOperation::AccountLink).to_string())?;
    match interpret_google_link_response(
        response.successful,
        &response.body,
        response.header_location.as_deref(),
    ) {
        GoogleLinkResponseDecision::ErrorClear(m) => {
            prefs.clear_pending_auth_flow(false);
            Err(m)
        }
        // NO commit: the source's JSONException propagates uncaught. (The
        // Err carries the AOSP message; the throw TYPE distinction lives
        // at the Kotlin call site.)
        GoogleLinkResponseDecision::ParseThrow(m) => Err(m),
        GoogleLinkResponseDecision::Url(u) => Ok(u),
    }
}

/// Verbatim port of isCycleCareAdmin: POST {restUrl}/rpc/is_cyclecare_admin,
/// true only on a successful "true" body; any failure is false.
pub fn is_cycle_care_admin(
    prefs: &mut AuthPrefsState,
    rest_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> bool {
    let token = match seams.resolve_token(prefs) {
        Some(t) => t,
        None => return false,
    };
    let _ = token;
    let url = format!("{}/rpc/is_cyclecare_admin", rest_url.trim_end_matches('/'));
    match transport("POST", &url, Some("{}")) {
        Ok(r) => interpret_admin_response(r.successful, Some(&r.body)),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth_flows::TokenVerdict;
    use crate::auth_link::HttpResponse;
    use std::path::PathBuf;

    const API: &str = "https://supabase.example.co/auth/v1";
    const REST: &str = "https://supabase.example.co/rest/v1";

    fn resp(ok: bool, body: &str, location: Option<&str>) -> HttpResponse {
        HttpResponse {
            successful: ok,
            body: body.to_string(),
            header_location: location.map(|s| s.to_string()),
        }
    }

    struct Row {
        id: String,
        setup: fn(&mut AuthPrefsState),
        queued: Vec<HttpResponse>,
        kind: String,
        outcome: String,
        action: String,
        reqs: String,
    }

    fn load_rows() -> Vec<Row> {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("fixtures/oracle/auth/results_google.tsv");
        let text = std::fs::read_to_string(p).unwrap();
        let mut rows = Vec::new();
        for line in text.lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.len() < 4 || cols[1] != "gurl" {
                continue;
            }
            let id = cols[0].to_string();
            let kv = |prefix: &str| -> String {
                cols.iter().find_map(|c| c.strip_prefix(prefix)).unwrap_or("").to_string()
            };
            let setup: fn(&mut AuthPrefsState) = match id.as_str() {
                "g02" => |p: &mut AuthPrefsState| { p.set_user_id(Some("u1".into())); },
                "g08" | "g09" => |p: &mut AuthPrefsState| {
                    p.access_token = Some("TOK".into());
                    p.set_user_id(Some("u1".into()));
                },
                "g03" | "g04" | "g05" | "g06" => |p: &mut AuthPrefsState| {
                    p.access_token = Some("TOK".into());
                    p.set_user_id(Some("anon-1".into()));
                },
                _ => |_| {},
            };
            let queued = match id.as_str() {
                "g03" => vec![resp(true, r#"{"url":"https://accounts.google.com/o/oauth2/auth?x=1"}"#, None)],
                "g04" => vec![resp(false, r#"{"code":400,"msg":"bad"}"#, None)],
                "g05" => vec![resp(true, "{}", Some("https://accounts.google.com/redir?y=2"))],
                "g06" => vec![resp(true, "{}", None)],
                "g08" => vec![resp(true, "true", None)],
                "g09" => vec![resp(true, "false", None)],
                _ => vec![],
            };
            rows.push(Row {
                id,
                setup,
                queued,
                kind: cols[2].to_string(),
                outcome: cols[3].to_string(),
                action: kv("action="),
                reqs: kv("reqs="),
            });
        }
        rows
    }

    #[test]
    fn google_and_admin_match_kotlin_corpus() {
        let rows = load_rows();
        assert_eq!(rows.len(), 10);
        for row in rows {
            let mut prefs = AuthPrefsState::fresh();
            (row.setup)(&mut prefs);
            let seams = FlowSeams {
                token_verdict: TokenVerdict::StubRawToken,
                now_seconds: 0,
                rng32: &|| [0x42u8; 32],
                new_device_id: &|| "dev-fixed".to_string(),
            };
            let mut queued = row.queued.into_iter().collect::<std::collections::VecDeque<_>>();
            let mut reqs = 0usize;
            let result: Result<String, String> = {
                let mut transport = |_: &str, _: &str, _: Option<&str>| -> Result<HttpResponse, String> {
                    reqs += 1;
                    queued.pop_front().ok_or_else(|| "probe: no response queued".to_string())
                };
                match row.id.as_str() {
                    "g01" | "g02" => create_google_sign_in_url(&mut prefs, API, &seams),
                    "g03" | "g04" | "g05" | "g06" | "g07" => {
                        resolve_google_auth_url(&mut prefs, API, &seams, &mut transport)
                    }
                    "g08" | "g09" | "g10" => {
                        Ok(is_cycle_care_admin(&mut prefs, REST, &seams, &mut transport).to_string())
                    }
                    other => panic!("unmapped {other}"),
                }
            };
            match row.kind.as_str() {
                "ok" => {
                    let url = result.unwrap_or_else(|e| panic!("{} expected ok, got {e}", row.id));
                    if row.id == "g01" || row.id == "g07" {
                        // challenge is random; compare shape + fixed parts
                        let expected_prefix = "https://supabase.example.co/auth/v1/authorize?provider=google&redirect_to=cyclecare%3A%2F%2Fauth%2Fcallback&code_challenge=";
                        assert!(url.starts_with(expected_prefix), "{} prefix", row.id);
                        assert!(url.ends_with("&code_challenge_method=s256"), "{} suffix", row.id);
                        let chal = url
                            .strip_prefix(expected_prefix)
                            .unwrap()
                            .strip_suffix("&code_challenge_method=s256")
                            .unwrap();
                        assert_eq!(chal.len(), 43, "{} challenge len", row.id);
                        assert_eq!(
                            prefs.pending_auth_action.as_deref(),
                            Some("GOOGLE"),
                            "{} action",
                            row.id
                        );
                    } else {
                        assert_eq!(url, row.outcome, "{} url", row.id);
                        assert_eq!(
                            prefs.pending_auth_action.as_deref().unwrap_or("null"),
                            row.action,
                            "{} action",
                            row.id
                        );
                    }
                }
                "throw" => {
                    let msg = result.unwrap_err();
                    assert_eq!(msg, row.outcome, "{} throw msg", row.id);
                    assert_eq!(
                        prefs.pending_auth_action.as_deref().unwrap_or("null"),
                        row.action,
                        "{} action",
                        row.id
                    );
                }
                "admin" => {
                    let v = result.unwrap();
                    assert_eq!(v, row.outcome, "{} admin", row.id);
                }
                other => panic!("unknown kind {other}"),
            }
            assert_eq!(reqs.to_string(), row.reqs, "{} reqs", row.id);
        }
    }

    #[test]
    fn google_seam_pins() {
        // sign_in_pre: secure first, then the signed-in gate; blank checks
        // are Kotlin isBlank (a whitespace-only userId blocks sign-in - the
        // older tree's is_empty diverged).
        assert_eq!(interpret_google_sign_in_pre(false, None),
            GooglePreDecision::Error(MSG_SIGNIN_UNAVAILABLE.to_string()));
        assert_eq!(interpret_google_sign_in_pre(true, Some("u1")),
            GooglePreDecision::Error(MSG_GOOGLE_SIGNOUT_FIRST.to_string()));
        assert_eq!(interpret_google_sign_in_pre(true, Some(" ")),
            GooglePreDecision::Error(MSG_GOOGLE_SIGNOUT_FIRST.to_string()));
        assert_eq!(interpret_google_sign_in_pre(true, None), GooglePreDecision::Proceed);
        assert_eq!(interpret_google_sign_in_pre(true, Some("")), GooglePreDecision::Proceed);

        // link gates: secure, then token presence (source order is by call
        // position; the seam itself is one gate each).
        assert_eq!(interpret_google_link_secure_pre(false),
            GooglePreDecision::Error(MSG_LINK_UNAVAILABLE.to_string()));
        assert_eq!(interpret_google_link_secure_pre(true), GooglePreDecision::Proceed);
        assert_eq!(interpret_google_link_token_pre(false),
            GooglePreDecision::Error(MSG_GUEST_NEEDS_REFRESH.to_string()));
        assert_eq!(interpret_google_link_token_pre(true), GooglePreDecision::Proceed);

        // resolve branch: ANONYMOUS + non-blank userId -> link.
        assert_eq!(interpret_google_resolve(Some("ANONYMOUS"), Some("u1")), GoogleResolveDecision::Link);
        assert_eq!(interpret_google_resolve(Some("ANONYMOUS"), Some(" ")), GoogleResolveDecision::SignIn);
        assert_eq!(interpret_google_resolve(Some("EMAIL"), Some("u1")), GoogleResolveDecision::SignIn);
        assert_eq!(interpret_google_resolve(None, None), GoogleResolveDecision::SignIn);

        // assembly: the two source shapes differ exactly by base trimming,
        // path and the skip flag.
        assert_eq!(
            assemble_google_authorize_url("https://api.example/auth/v1", "chal", false),
            "https://api.example/auth/v1/authorize?provider=google&redirect_to=cyclecare%3A%2F%2Fauth%2Fcallback&code_challenge=chal&code_challenge_method=s256");
        assert_eq!(
            assemble_google_authorize_url("https://api.example/auth/v1/", "chal", true),
            "https://api.example/auth/v1/user/identities/authorize?provider=google&redirect_to=cyclecare%3A%2F%2Fauth%2Fcallback&code_challenge=chal&code_challenge_method=s256&skip_http_redirect=true");
        // URLEncoder.encode: space -> '+', '/' -> %2F, b64url chars kept.
        assert_eq!(url_encode("a b/c:d"), "a+b%2Fc%3Ad");
        assert_eq!(url_encode("-._*"), "-._*");

        // link_response: status failure -> ErrorClear via corpus mapping.
        assert_eq!(
            interpret_google_link_response(false, "x", None),
            GoogleLinkResponseDecision::ErrorClear(
                fallback(AuthOperation::AccountLink).to_string()));
        // Parse failure -> ParseThrow with the AOSP JSONTokener message (NO
        // commit; the older tree swallowed this into the fallback branch).
        assert_eq!(
            interpret_google_link_response(true, "", None),
            GoogleLinkResponseDecision::ParseThrow("End of input at character 0 of ".to_string()));
        // A parseable NON-object -> the JSONObject ctor's typeMismatch.
        assert_eq!(
            interpret_google_link_response(true, "[]", None),
            GoogleLinkResponseDecision::ParseThrow(
                "Value [] of type org.json.JSONArray cannot be converted to JSONObject".to_string()));
        assert_eq!(
            interpret_google_link_response(true, "5", None),
            GoogleLinkResponseDecision::ParseThrow(
                "Value 5 of type java.lang.Integer cannot be converted to JSONObject".to_string()));
        // optString coercion: an explicit null url is the STRING "null" -
        // non-blank, so it IS the resolved url (weird, source-true).
        assert_eq!(
            interpret_google_link_response(true, r#"{"url":null}"#, Some("https://loc.example")),
            GoogleLinkResponseDecision::Url("null".to_string()));
        // Blank "url" falls to the Location header (isBlank, so a
        // whitespace-only url also falls - the older tree's is_empty
        // diverged); blank Location -> clear + fallback.
        assert_eq!(
            interpret_google_link_response(true, r#"{"url":" "}"#, Some("https://loc.example")),
            GoogleLinkResponseDecision::Url("https://loc.example".to_string()));
        assert_eq!(
            interpret_google_link_response(true, r#"{"url":"x"}"#, Some("https://loc.example")),
            GoogleLinkResponseDecision::Url("x".to_string()));
        assert_eq!(
            interpret_google_link_response(true, "{}", Some("  ")),
            GoogleLinkResponseDecision::ErrorClear(
                fallback(AuthOperation::AccountLink).to_string()));

        // admin: Kotlin trim (probe-locked set: NEL U+0085 is NOT trimmed;
        // Rust's std trim would) + simple-mapping ignoreCase on "true".
        assert!(interpret_admin_response(true, Some("true")));
        assert!(interpret_admin_response(true, Some(" TRUE\n")));
        assert!(!interpret_admin_response(true, Some("true\u{85}")));
        assert!(!interpret_admin_response(true, Some("false")));
        assert!(!interpret_admin_response(false, Some("true")));
        assert!(!interpret_admin_response(true, None));
    }
}
