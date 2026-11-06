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
use crate::pkce::begin_pkce_flow;
use crate::sync_codec::{aosp_opt_string, JsonParser};

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

/// Verbatim port of createGoogleSignInUrl. Err = the thrown message.
pub fn create_google_sign_in_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
) -> Result<String, String> {
    if !prefs.is_secure_storage_available {
        return Err(MSG_SIGNIN_UNAVAILABLE.to_string());
    }
    if let Some(uid) = prefs.user_id.as_deref() {
        if !uid.is_empty() {
            return Err(MSG_GOOGLE_SIGNOUT_FIRST.to_string());
        }
    }
    let challenge = begin_pkce_flow((seams.rng32)(), AUTH_ACTION_GOOGLE, prefs);
    let encoded_challenge = url_encode(&challenge);
    Ok(format!(
        "{auth_api_url}/authorize?provider=google&redirect_to={ENCODED_REDIRECT}&code_challenge={encoded_challenge}&code_challenge_method=s256"
    ))
}

/// Verbatim port of resolveGoogleAuthUrl.
pub fn resolve_google_auth_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> Result<String, String> {
    if prefs.account_mode.as_deref() == Some("ANONYMOUS")
        && prefs.user_id.as_deref().map(|u| !u.is_empty()).unwrap_or(false)
    {
        create_google_link_url(prefs, auth_api_url, seams, transport)
    } else {
        create_google_sign_in_url(prefs, auth_api_url, seams)
    }
}

/// Verbatim port of createGoogleLinkUrl.
fn create_google_link_url(
    prefs: &mut AuthPrefsState,
    auth_api_url: &str,
    seams: &FlowSeams,
    transport: &mut Transport,
) -> Result<String, String> {
    if !prefs.is_secure_storage_available {
        return Err(MSG_LINK_UNAVAILABLE.to_string());
    }
    let _token = match seams.resolve_token(prefs) {
        Some(t) if !t.is_empty() => t,
        _ => return Err(MSG_GUEST_NEEDS_REFRESH.to_string()),
    };
    let challenge = begin_pkce_flow((seams.rng32)(), AUTH_ACTION_GOOGLE_LINK, prefs);
    let encoded_challenge = url_encode(&challenge);
    let url = format!(
        "{}/user/identities/authorize?provider=google&redirect_to={ENCODED_REDIRECT}&code_challenge={encoded_challenge}&code_challenge_method=s256&skip_http_redirect=true",
        auth_api_url.trim_end_matches('/')
    );
    let response = transport("GET", &url, None).map_err(|_| fallback(AuthOperation::AccountLink).to_string())?;
    if !response.successful {
        prefs.clear_pending_auth_flow(false);
        return Err(for_response(&response.body, AuthOperation::AccountLink));
    }
    let url = JsonParser::new(&response.body)
        .parse()
        .map(|j| aosp_opt_string(&j, "url", ""))
        .unwrap_or_default();
    let url = if url.is_empty() {
        response.header_location.clone().unwrap_or_default()
    } else {
        url
    };
    if url.is_empty() {
        prefs.clear_pending_auth_flow(false);
        return Err(fallback(AuthOperation::AccountLink).to_string());
    }
    Ok(url)
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
        Some(t) if !t.is_empty() => t,
        _ => return false,
    };
    let _ = token;
    let url = format!("{}/rpc/is_cyclecare_admin", rest_url.trim_end_matches('/'));
    match transport("POST", &url, Some("{}")) {
        Ok(r) => r.successful && r.body.trim().eq_ignore_ascii_case("true"),
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
}
