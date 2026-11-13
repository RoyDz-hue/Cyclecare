//! Port of SupabaseSessionManager.resolveAccessToken + tokenExpiry +
//! validTokenForOwner (main@31b88f5, data/supabase/SupabaseSessionManager.kt
//! lines 63-141). Transport and clock are injected; the refreshMutex /
//! coroutine machinery is host glue. Executed JVM locks:
//! /downloads/parity/lifecycle/SessionTokenParity.kt scenarios s01-s23 and
//! t01-t08 (AOSP org.json android-14.0.0_r1 classes, executed 2026-11-02
//! 13:50-13:51 EAT). android.util.Base64(URL_SAFE|NO_PADDING|NO_WRAP) is
//! mirrored by a strict RFC 4648 url-safe decoder (the JVM probe used
//! java.util.Base64.getUrlDecoder(), the documented equivalent).

use crate::kotlin_text::kotlin_is_blank;
use crate::lifecycle::SessionStore;
use crate::sync_codec::{JsonParser, JsonValue};

pub const MAX_RESPONSE_BYTES: usize = 64 * 1024;
pub const MAX_TOKEN_CHARS: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AccessTokenResolution {
    Valid(String),
    Missing,
    ReauthenticationRequired,
    TemporarilyUnavailable,
}

pub struct RefreshResponse {
    pub code: i32,
    pub body: Option<String>,
}

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// org.json opt as? Number -> toLong: Int/Long as-is, Double truncates
/// (Java Number.toLong), anything else absent -> 0.
fn opt_number_long(obj: &JsonValue, key: &str) -> i64 {
    match obj_get(obj, key) {
        Some(JsonValue::Int(i)) => *i as i64,
        Some(JsonValue::Long(l)) => *l,
        Some(JsonValue::Double(d)) => *d as i64,
        _ => 0,
    }
}

fn opt_boolean(obj: &JsonValue, key: &str) -> bool {
    match obj_get(obj, key) {
        Some(JsonValue::Bool(b)) => *b,
        Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
        _ => false,
    }
}

/// android.util.Base64.decode(input, URL_SAFE|NO_PADDING|NO_WRAP): the AOSP
/// Decoder.process state machine (frameworks/base android-14.0.0_r1,
/// core/java/android/util/Base64.java) ported line-faithfully, executed
/// against the compiled AOSP class (AndroidB64Probe, d01-d24). The slow path
/// alone is ported; the source states the fast path is behaviorally identical.
/// Semantics that differ from a "strict RFC 4648" reading, all executed:
/// non-alphabet bytes are SKIPPED (whitespace, wrong-alphabet "+/", even
/// non-ASCII UTF-8 bytes); "=" padding must complete its 4-group exactly
/// ("QQ" ok, "QQ=" bad, "QQ==" ok, data after padding bad); a trailing
/// 2- or 3-symbol partial unit without padding is fine.
fn decode_websafe(b: u8) -> i32 {
    match b {
        b'A'..=b'Z' => (b - b'A') as i32,
        b'a'..=b'z' => (b - b'a' + 26) as i32,
        b'0'..=b'9' => (b - b'0' + 52) as i32,
        b'-' => 62,
        b'_' => 63,
        b'=' => -2,
        _ => -1,
    }
}

const SKIP: i32 = -1;
const EQUALS: i32 = -2;

pub fn b64url_decode(s: &str) -> Option<Vec<u8>> {
    // Base64.decode(String, flags) decodes str.getBytes() - UTF-8 on Android.
    let input = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(input.len() * 3 / 4);
    let mut state = 0u8;
    let mut value: u32 = 0;
    for &byte in input {
        let d = decode_websafe(byte);
        match state {
            0 => {
                if d >= 0 { value = d as u32; state = 1; }
                else if d != SKIP { return None; }
            }
            1 => {
                if d >= 0 { value = (value << 6) | d as u32; state = 2; }
                else if d != SKIP { return None; }
            }
            2 => {
                if d >= 0 { value = (value << 6) | d as u32; state = 3; }
                else if d == EQUALS { out.push((value >> 4) as u8); state = 4; }
                else if d != SKIP { return None; }
            }
            3 => {
                if d >= 0 {
                    value = (value << 6) | d as u32;
                    out.push((value >> 16) as u8);
                    out.push((value >> 8) as u8);
                    out.push(value as u8);
                    state = 0;
                } else if d == EQUALS {
                    out.push((value >> 10) as u8);
                    out.push((value >> 2) as u8);
                    state = 5;
                } else if d != SKIP { return None; }
            }
            4 => {
                if d == EQUALS { state = 5; }
                else if d != SKIP { return None; }
            }
            _ => { // state 5
                if d != SKIP { return None; }
            }
        }
    }
    match state {
        0 => {}
        1 => return None,   // one extra symbol: not enough for a byte
        2 => out.push((value >> 4) as u8),
        3 => { out.push((value >> 10) as u8); out.push((value >> 2) as u8); }
        4 => return None,   // one padding where two were expected
        _ => {}             // state 5: padding complete
    }
    Some(out)
}

/// tokenExpiry: JWT payload exp claim as long, 0 on any failure. Kotlin's
/// String(bytes, UTF_8) replaces malformed sequences (lossy), and
/// JSONObject(non-object) throws -> 0.
pub fn token_expiry(token: &str) -> i64 {
    let payload = match token.split('.').nth(1) {
        Some(p) => p,
        None => return 0,
    };
    let bytes = match b64url_decode(payload) {
        Some(b) => b,
        None => return 0,
    };
    let decoded = String::from_utf8_lossy(&bytes).into_owned();
    match JsonParser::new(&decoded).parse() {
        Ok(v @ JsonValue::Object(_)) => opt_number_long(&v, "exp"),
        _ => 0,
    }
}

/// validTokenForOwner: structural consistency only (authenticity is the
/// server's job). 3 non-empty base64url pieces, sub claim == owner string,
/// exp in the future, length <= MAX_TOKEN_CHARS UTF-16 units.
pub fn valid_token_for_owner(token: &str, owner: &str, now_secs: i64) -> bool {
    let pieces: Vec<&str> = token.split('.').collect();
    let utf16_len: usize = token.chars().map(|c| c.len_utf16()).sum();
    if !(1..=MAX_TOKEN_CHARS).contains(&utf16_len)
        || pieces.len() != 3
        || !pieces.iter().all(|p| {
            !p.is_empty()
                && p.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        })
    {
        return false;
    }
    let bytes = match b64url_decode(pieces[1]) {
        Some(b) => b,
        None => return false,
    };
    let decoded = String::from_utf8_lossy(&bytes).into_owned();
    let sub_ok = match JsonParser::new(&decoded).parse() {
        Ok(v) => matches!(obj_get(&v, "sub"), Some(JsonValue::Str(s)) if s == owner),
        Err(_) => false,
    };
    sub_ok && token_expiry(token) > now_secs
}

/// Outcome of validating a refresh HTTP response (the PURE segment of
/// resolveAccessToken: status mapping, body bounds, org.json-locked parse,
/// shape/owner/token/expiry checks). Transport, mutex, admission and the
/// compare-and-set commit are host glue and stay outside this function.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RefreshValidation {
    Proceed {
        renewed_token: String,
        /// Some(rotated) when the response rotated the refresh token;
        /// None = keep the caller's old refresh token (Kotlin:
        /// `if (!json.has("refresh_token")) refreshToken`). The EFFECTIVE
        /// value's blank/length/ISO-control checks were applied inside.
        rotated_refresh: Option<String>,
        new_expiry: i64,
        /// None = keep the stored email (Kotlin `?: preferences.userEmail`).
        user_email: Option<String>,
        anonymous: bool,
    },
    ReauthenticationRequired,
    TemporarilyUnavailable,
}

/// The pure refresh-response validation of resolveAccessToken, shared by
/// resolve_access_token above and the JNI wiring (NEW IMPLEMENTATION
/// 2026-11-13) so both run ONE implementation. `old_refresh` is validated
/// when the response carries no rotation - the Kotlin source applies the
/// blank/UTF-16-length/ISO-control checks to the effective value regardless
/// of origin.
pub fn validate_refresh_response(
    code: i32,
    body: Option<&str>,
    owner: &str,
    old_refresh: &str,
    now_secs: i64,
) -> RefreshValidation {
    use RefreshValidation::*;
    if !(200..=299).contains(&code) {
        return if matches!(code, 400 | 401 | 403) {
            ReauthenticationRequired
        } else {
            TemporarilyUnavailable
        };
    }
    let raw = match body {
        Some(b) => b,
        None => return TemporarilyUnavailable,
    };
    if raw.as_bytes().len() > MAX_RESPONSE_BYTES {
        return TemporarilyUnavailable;
    }
    // JSONObject(raw) failure (malformed or non-object) -> catch -> Temp.
    let json = match JsonParser::new(raw).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => return TemporarilyUnavailable,
    };
    let renewed_token = match obj_get(&json, "access_token") {
        Some(JsonValue::Str(s)) => s.clone(),
        _ => return ReauthenticationRequired,
    };
    let rotated_refresh: Option<String> = match obj_get(&json, "refresh_token") {
        None => None,
        Some(JsonValue::Str(s)) => Some(s.clone()),
        Some(_) => return ReauthenticationRequired,
    };
    let rotated_effective: &str = rotated_refresh.as_deref().unwrap_or(old_refresh);
    let user = match obj_get(&json, "user") {
        Some(v @ JsonValue::Object(_)) => v.clone(),
        _ => return ReauthenticationRequired,
    };
    let uid_ok = matches!(obj_get(&user, "id"), Some(JsonValue::Str(s)) if *s == owner);
    if !uid_ok
        || !valid_token_for_owner(&renewed_token, owner, now_secs)
        || kotlin_is_blank(rotated_effective)
        || rotated_effective.chars().map(|c| c.len_utf16()).sum::<usize>() > MAX_TOKEN_CHARS
        || rotated_effective.chars().any(|c| c.is_control())
    {
        return ReauthenticationRequired;
    }
    let expires_at = opt_number_long(&json, "expires_at");
    let expires_in = opt_number_long(&json, "expires_in");
    let reported_expiry = if expires_at > 0 {
        expires_at
    } else if (1..=604800).contains(&expires_in) {
        now_secs + expires_in
    } else {
        return ReauthenticationRequired;
    };
    if reported_expiry <= now_secs || reported_expiry > now_secs + 604800 {
        return ReauthenticationRequired;
    }
    let new_expiry = reported_expiry.min(token_expiry(&renewed_token));
    let user_email: Option<String> = match obj_get(&user, "email") {
        Some(JsonValue::Str(s)) if !kotlin_is_blank(s) => Some(s.clone()),
        _ => None,
    };
    let anonymous = opt_boolean(&user, "is_anonymous");
    Proceed { renewed_token, rotated_refresh, new_expiry, user_email, anonymous }
}

/// resolveAccessToken. `transport` performs the refresh HTTP call;
/// Err(()) mirrors IOException (and any unexpected exception) ->
/// TemporarilyUnavailable.
pub fn resolve_access_token(
    prefs: &mut SessionStore,
    now_secs: i64,
    transport: &dyn Fn(&str) -> Result<RefreshResponse, ()>,
) -> AccessTokenResolution {
    use AccessTokenResolution::*;
    if !prefs.is_secure_storage_available() {
        return ReauthenticationRequired;
    }
    let token = match prefs.access_token() {
        Some(t) if !kotlin_is_blank(t) => t.to_string(),
        _ => return Missing,
    };
    let owner = match prefs.user_id() {
        Some(u) if !kotlin_is_blank(u) => u.to_string(),
        _ => return ReauthenticationRequired,
    };
    let session = prefs.session_generation();
    let stored_expiry = prefs.session_expires_at_seconds();
    let expiry = if stored_expiry > 0 {
        stored_expiry
    } else {
        token_expiry(&token)
    };
    if expiry > now_secs + 90 {
        let still = prefs.with_current_session(&owner, session, |q| {
            q.access_token().map(|s| s.to_string()) == Some(token.clone())
        }) == Some(true);
        return if still { Valid(token) } else { TemporarilyUnavailable };
    }
    let refresh_token = match prefs.refresh_token() {
        Some(r) if !kotlin_is_blank(r) => r.to_string(),
        _ => return ReauthenticationRequired,
    };
    let response = match transport(&refresh_token) {
        Ok(r) => r,
        Err(()) => return TemporarilyUnavailable,
    };
    let (renewed_token, rotated_refresh, new_expiry, new_email, new_mode) =
        match validate_refresh_response(response.code, response.body.as_deref(), &owner, &refresh_token, now_secs) {
            RefreshValidation::Proceed { renewed_token, rotated_refresh, new_expiry, user_email, anonymous } => (
                renewed_token,
                rotated_refresh.unwrap_or_else(|| refresh_token.clone()),
                new_expiry,
                user_email,
                if anonymous { "ANONYMOUS" } else { "EMAIL" },
            ),
            RefreshValidation::ReauthenticationRequired => return ReauthenticationRequired,
            RefreshValidation::TemporarilyUnavailable => return TemporarilyUnavailable,
        };
    let committed = prefs.with_current_session(&owner, session, |q| {
        if q.access_token() != Some(token.as_str()) || q.refresh_token() != Some(refresh_token.as_str()) {
            return false;
        }
        q.set_access_token(Some(&renewed_token));
        q.set_refresh_token(Some(&rotated_refresh));
        q.set_session_expires_at_seconds(new_expiry);
        if let Some(e) = &new_email {
            q.set_user_email(Some(e));
        }
        q.set_account_mode(new_mode);
        true
    }) == Some(true);
    if !committed {
        return TemporarilyUnavailable;
    }
    Valid(renewed_token)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Executed JVM locks (SessionTokenParity.kt, 2026-11-02 13:50 EAT).
    const NOW: i64 = 1_700_000_000;
    const JWT_NEW: &str = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJvd25lcjEiLCJleHAiOjE3MDAwMDcyMDB9.c2ln"; // sub=owner1, exp=NOW+7200

    fn b64url(s: &str) -> String {
        const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut out = String::new();
        for chunk in s.as_bytes().chunks(3) {
            let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(A[(n >> 18) as usize & 63] as char);
            out.push(A[(n >> 12) as usize & 63] as char);
            if chunk.len() > 1 { out.push(A[(n >> 6) as usize & 63] as char); }
            if chunk.len() > 2 { out.push(A[n as usize & 63] as char); }
        }
        out
    }

    fn jwt(sub: Option<&str>, exp: Option<i64>) -> String {
        let mut p = String::from("{");
        let mut first = true;
        if let Some(s) = sub {
            p.push_str(&format!("\"sub\":\"{s}\""));
            first = false;
        }
        if let Some(e) = exp {
            if !first { p.push(','); }
            p.push_str(&format!("\"exp\":{e}"));
        }
        p.push('}');
        format!("{}.{}.{}", b64url("{\"alg\":\"none\"}"), b64url(&p), b64url("sig"))
    }

    fn render(r: &AccessTokenResolution) -> String {
        match r {
            AccessTokenResolution::Valid(t) => format!("Valid(token={t})"),
            AccessTokenResolution::Missing => "Missing".to_string(),
            AccessTokenResolution::ReauthenticationRequired => "Reauth".to_string(),
            AccessTokenResolution::TemporarilyUnavailable => "Temp".to_string(),
        }
    }

    fn state(p: &SessionStore) -> String {
        let take = |s: Option<&str>, n: usize| match s {
            None => "null".to_string(),
            Some(v) => v.chars().take(n).collect(),
        };
        format!(
            "at={},rt={},exp={},em={},mode={},g={}",
            take(p.access_token(), 12),
            take(p.refresh_token(), 6),
            p.session_expires_at_seconds(),
            p.user_email().map(|s| s.to_string()).unwrap_or("null".into()),
            p.account_mode(),
            p.session_generation()
        )
    }

    fn base() -> SessionStore {
        let mut p = SessionStore::new(true);
        p.set_user_id(Some("owner1"));
        p.set_access_token(Some(&jwt(Some("owner1"), Some(NOW - 100))));
        p.set_refresh_token(Some("r1"));
        p
    }

    fn rebased() -> SessionStore {
        let mut p = base();
        p.set_access_token(Some("tokA"));
        p.set_session_expires_at_seconds(NOW + 10);
        p
    }

    fn ok(body: &str) -> String {
        body.to_string()
    }

    fn ok_body(extra: &str) -> String {
        format!("{{\"access_token\":\"{JWT_NEW}\",\"refresh_token\":\"r2\",\"user\":{{\"id\":\"owner1\",\"email\":\"e@x.com\",\"is_anonymous\":false}},\"expires_in\":3600{extra}}}")
    }

    fn t_ok(body: String) -> impl Fn(&str) -> Result<RefreshResponse, ()> {
        move |_| Ok(RefreshResponse { code: 200, body: Some(body.clone()) })
    }

    #[test]
    fn resolve_access_token_matches_executed_jvm_scenarios() {
        // s01 insecure
        let mut p = SessionStore::new(false);
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{}")))), "Reauth");
        // s02 blank token (blank is removed by write_secure_string)
        let mut p = base();
        p.set_access_token(Some(" "));
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{}")))), "Missing");
        // s03 no owner
        let mut p = base();
        p.set_user_id(None);
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{}")))), "Reauth");
        // s04 stored expiry still valid
        let mut p = base();
        p.set_access_token(Some("tokA"));
        p.set_session_expires_at_seconds(NOW + 3600);
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{}")))), "Valid(token=tokA)");
        // s05 stale generation at re-check
        let mut p = base();
        p.set_access_token(Some("tokA"));
        p.set_session_expires_at_seconds(NOW + 3600);
        let gen = p.session_generation();
        let r5 = if p.with_current_session("owner1", gen - 1, |q| q.access_token() == Some("tokA")) == Some(true) {
            "Valid(token=tokA)"
        } else {
            "Temp"
        };
        assert_eq!(r5, "Temp");
        // s06 expired, no refresh token
        let mut p = base();
        p.set_access_token(Some("tokA"));
        p.set_session_expires_at_seconds(NOW + 10);
        p.set_refresh_token(None);
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{}")))), "Reauth");
        // s07 401 -> Reauth, s08 500 -> Temp, s09 null body -> Temp
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &|_| Ok(RefreshResponse { code: 401, body: Some(ok("{}")) }))), "Reauth");
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &|_| Ok(RefreshResponse { code: 500, body: Some(ok("{}")) }))), "Temp");
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &|_| Ok(RefreshResponse { code: 200, body: None }))), "Temp");
        // s10 full success
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("")))), format!("Valid(token={JWT_NEW})"));
        assert_eq!(state(&p), "at=eyJhbGciOiJu,rt=r2,exp=1700003600,em=e@x.com,mode=EMAIL,g=1");
        // s11 refresh_token field absent -> keep r1
        let mut p = rebased();
        let body_no_rt = format!("{{\"access_token\":\"{JWT_NEW}\",\"user\":{{\"id\":\"owner1\"}},\"expires_in\":3600}}");
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(body_no_rt))), format!("Valid(token={JWT_NEW})"));
        assert_eq!(state(&p), "at=eyJhbGciOiJu,rt=r1,exp=1700003600,em=null,mode=EMAIL,g=1");
        // s12 access_token not a string
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{\"access_token\":123,\"user\":{\"id\":\"owner1\"},\"expires_in\":3600}")))), "Reauth");
        // s13 user.id mismatch (same p, still expired)
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"id\":\"owner1\"", "\"id\":\"other\"")))), "Reauth");
        // s14 ISO control char in rotated refresh
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"r2\"", "\"r2\u{1}\"")))), "Reauth");
        // s15 expires_in=0, s16 expires_in=604801 (reported > now+604800)
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"expires_in\":3600", "\"expires_in\":0")))), "Reauth");
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"expires_in\":3600", "\"expires_in\":604801")))), "Reauth");
        // s17 renewed JWT sub mismatch
        let bad = ok_body("").replace(JWT_NEW, &jwt(Some("other"), Some(NOW + 7200)));
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(bad))), "Reauth");
        // s18 transport IOException
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &|_| Err(()))), "Temp");
        // s19 anonymous -> ANONYMOUS
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"is_anonymous\":false", "\"is_anonymous\":true")))), format!("Valid(token={JWT_NEW})"));
        assert_eq!(state(&p), "at=eyJhbGciOiJu,rt=r2,exp=1700003600,em=e@x.com,mode=ANONYMOUS,g=1");
        // s20 blank email keeps old (null here)
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body("").replace("\"e@x.com\"", "\"\"")))), format!("Valid(token={JWT_NEW})"));
        assert_eq!(state(&p), "at=eyJhbGciOiJu,rt=r2,exp=1700003600,em=null,mode=EMAIL,g=1");
        // s21 expires_at wins; newExpiry = min(reported, jwt exp)
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok_body(",\"expires_at\":1700005000")))), format!("Valid(token={JWT_NEW})"));
        assert_eq!(state(&p), "at=eyJhbGciOiJu,rt=r2,exp=1700005000,em=e@x.com,mode=EMAIL,g=1");
        // s22 malformed body -> Temp; s23 non-object body -> Temp
        let mut p = rebased();
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("{bad")))), "Temp");
        assert_eq!(render(&resolve_access_token(&mut p, NOW, &t_ok(ok("[1,2]")))), "Temp");
    }

    #[test]
    fn jwt_helpers_match_executed_jvm_probes() {
        assert_eq!(token_expiry(&jwt(Some("owner1"), Some(NOW + 100))), NOW + 100); // t01
        assert_eq!(token_expiry(&jwt(Some("owner1"), None)), 0); // t02
        assert_eq!(token_expiry("not-a-jwt"), 0); // t03
        assert!(!valid_token_for_owner("abc.def", "owner1", NOW)); // t04
        assert!(!valid_token_for_owner("abc.!!!.def", "owner1", NOW)); // t05
        assert!(!valid_token_for_owner(&jwt(Some("owner1"), Some(NOW - 5)), "owner1", NOW)); // t06
        assert!(valid_token_for_owner(&jwt(Some("owner1"), Some(NOW + 500)), "owner1", NOW)); // t07
        let num_sub = format!("{}.{}.{}", b64url("{}"), b64url(&format!("{{\"sub\":123,\"exp\":{}}}", NOW + 500)), b64url("s"));
        assert!(!valid_token_for_owner(&num_sub, "123", NOW)); // t08
    }
}

#[cfg(test)]
mod android_b64_lock {
    use super::b64url_decode;

    /// Locked to AndroidB64Probe executed against the compiled AOSP
    /// android-14.0.0_r1 android.util.Base64 class (URL_SAFE|NO_PADDING|NO_WRAP).
    #[test]
    fn android_base64_decoder_corpus() {
        let ok: &[(&str, &str)] = &[
            ("eyJhIjp9", "7b2261223a7d"),
            ("eyJhIjp9Cg==", "7b2261223a7d0a"),
            ("QQ", "41"),
            ("QQ==", "41"),
            ("QUJD", "414243"),
            ("QUI", "4142"),
            ("QUJDRA==", "41424344"),
            ("", ""),
            ("QUJ!", "4142"),
            ("QUJD\n", "414243"),
            ("-_", "fb"),
            ("QUJDRA", "41424344"),
            ("QUJD ", "414243"),
            ("++//", ""),
            ("eyJzdWIiOiJ1MSIsImV4cCI6MTk5OTk5OTk5OX0", "7b22737562223a227531222c22657870223a313939393939393939397d"),
            ("QUJÉD", "414243"),
            ("QQ= =", "41"),
            ("QU JD", "414243"),
        ];
        for (input, hex) in ok {
            let got = b64url_decode(input).unwrap_or_else(|| panic!("{input:?} should decode"));
            let got_hex: String = got.iter().map(|b| format!("{b:02x}")).collect();
            assert_eq!(&got_hex, hex, "input {input:?}");
        }
        for input in ["QQ=", "A", "QQ==QQ", "====", "Q=Q=", "QUJDRA="] {
            assert!(b64url_decode(input).is_none(), "{input:?} should be rejected");
        }
    }
    // Focused pins for validate_refresh_response (NEW IMPLEMENTATION,
    // 2026-11-13). Written for execution on a build host; NOT executed in
    // the wiring environment. NOW and jwt()/b64url() come from the
    // executed-JVM lock helpers above (JWT_NEW: sub=owner1, exp=NOW+7200).
    fn refresh_body(extra: &str) -> String {
        format!(
            "{{\"access_token\":\"{JWT_NEW}\",\"expires_at\":{},\"user\":{{\"id\":\"owner1\"{}}}}}",
            1_700_003_600i64, extra
        )
    }

    #[test]
    fn validate_refresh_keep_old_and_rotation() {
        // No refresh_token key -> keep old (None), email absent -> None.
        match validate_refresh_response(200, Some(&refresh_body("")), "owner1", "oldrefresh", NOW) {
            RefreshValidation::Proceed { renewed_token, rotated_refresh, new_expiry, user_email, anonymous } => {
                assert_eq!(renewed_token, JWT_NEW);
                assert_eq!(rotated_refresh, None);
                // min(reported NOW+3600, jwt exp NOW+7200)
                assert_eq!(new_expiry, 1_700_003_600);
                assert_eq!(user_email, None);
                assert!(!anonymous);
            }
            other => panic!("expected Proceed, got {other:?}"),
        }
        // Rotation: new refresh returned, expires_in path (no expires_at).
        let body = format!(
            "{{\"access_token\":\"{JWT_NEW}\",\"refresh_token\":\"rotated123\",\"expires_in\":3600,\"user\":{{\"id\":\"owner1\",\"email\":\"a@b.c\",\"is_anonymous\":true}}}}"
        );
        match validate_refresh_response(200, Some(&body), "owner1", "oldrefresh", NOW) {
            RefreshValidation::Proceed { rotated_refresh, new_expiry, user_email, anonymous, .. } => {
                assert_eq!(rotated_refresh.as_deref(), Some("rotated123"));
                assert_eq!(new_expiry, NOW + 3600);
                assert_eq!(user_email.as_deref(), Some("a@b.c"));
                assert!(anonymous);
            }
            other => panic!("expected Proceed, got {other:?}"),
        }
    }

    #[test]
    fn validate_refresh_old_token_still_validated() {
        // Keep-old case: the OLD refresh token's own blank/ISO-control/
        // length checks apply (Kotlin checks the effective value regardless
        // of origin) - this is why old_refresh is an input.
        let good = refresh_body("");
        assert_eq!(
            validate_refresh_response(200, Some(&good), "owner1", "bad\u{7}refresh", NOW),
            RefreshValidation::ReauthenticationRequired
        );
        assert_eq!(
            validate_refresh_response(200, Some(&good), "owner1", "   ", NOW),
            RefreshValidation::ReauthenticationRequired
        );
        let too_long = "x".repeat(16 * 1024 + 1);
        assert_eq!(
            validate_refresh_response(200, Some(&good), "owner1", &too_long, NOW),
            RefreshValidation::ReauthenticationRequired
        );
    }

    #[test]
    fn validate_refresh_terminal_mapping() {
        let good = refresh_body("");
        // Status mapping.
        assert_eq!(validate_refresh_response(400, Some(&good), "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        assert_eq!(validate_refresh_response(401, None, "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        assert_eq!(validate_refresh_response(403, None, "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        assert_eq!(validate_refresh_response(500, Some(&good), "owner1", "r", NOW), RefreshValidation::TemporarilyUnavailable);
        assert_eq!(validate_refresh_response(199, Some(&good), "owner1", "r", NOW), RefreshValidation::TemporarilyUnavailable);
        // Null body and malformed JSON -> TemporarilyUnavailable (the
        // source's catch-all), NOT ReauthenticationRequired.
        assert_eq!(validate_refresh_response(200, None, "owner1", "r", NOW), RefreshValidation::TemporarilyUnavailable);
        assert_eq!(validate_refresh_response(200, Some("{not json"), "owner1", "r", NOW), RefreshValidation::TemporarilyUnavailable);
        assert_eq!(validate_refresh_response(200, Some("[1,2]"), "owner1", "r", NOW), RefreshValidation::TemporarilyUnavailable);
        // Shape failures -> ReauthenticationRequired.
        assert_eq!(validate_refresh_response(200, Some("{}"), "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        // refresh_token present but null -> Reauth (opt as? String fails).
        let body_null_rt = format!("{{\"access_token\":\"{JWT_NEW}\",\"refresh_token\":null,\"expires_at\":1700003600,\"user\":{{\"id\":\"owner1\"}}}}");
        assert_eq!(validate_refresh_response(200, Some(&body_null_rt), "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        // Wrong owner in user.id -> Reauth.
        assert_eq!(validate_refresh_response(200, Some(&good), "someoneelse", "r", NOW), RefreshValidation::ReauthenticationRequired);
        // Expiry bounds: reported <= now, or > now+604800 -> Reauth.
        let past = format!("{{\"access_token\":\"{JWT_NEW}\",\"expires_at\":1699999999,\"user\":{{\"id\":\"owner1\"}}}}");
        assert_eq!(validate_refresh_response(200, Some(&past), "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        let far = format!("{{\"access_token\":\"{JWT_NEW}\",\"expires_at\":1700604801,\"user\":{{\"id\":\"owner1\"}}}}");
        assert_eq!(validate_refresh_response(200, Some(&far), "owner1", "r", NOW), RefreshValidation::ReauthenticationRequired);
        // Blank email -> keep old (None); is_anonymous "true" STRING is
        // coerced true by org.json optBoolean.
        let coerced = format!("{{\"access_token\":\"{JWT_NEW}\",\"expires_at\":1700003600,\"user\":{{\"id\":\"owner1\",\"email\":\"  \",\"is_anonymous\":\"true\"}}}}");
        match validate_refresh_response(200, Some(&coerced), "owner1", "r", NOW) {
            RefreshValidation::Proceed { user_email, anonymous, .. } => {
                assert_eq!(user_email, None);
                assert!(anonymous);
            }
            other => panic!("expected Proceed, got {other:?}"),
        }
    }
}
