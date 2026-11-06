//! Rust port of CycleCare auth-callback URI handling.
//!
//! Sources probed and mirrored:
//! - AOSP android-14.0.0_r1 android/net/UriCodec.java `decode(s, convertPlus=false,
//!   UTF-8, throwOnFailure=false)` — compiled from source and executed in
//!   /downloads/parity/auth (results_decode.tsv, 15 rows).
//! - AOSP android-14.0.0_r1 android/net/Uri.java StringUri structural parsing
//!   (scheme separator, authority, userInfo lastIndexOf('@'), findPortSeparator
//!   reverse digit scan, parsePort NumberFormatException -> -1) — executed rows in
//!   results_struct.tsv (14 rows).
//! - Verbatim com.cyclecare.app.data.supabase.SupabaseAuthService
//!   `parseAuthCallbackParameters` (31b88f5) — executed rows in results_authcb.tsv
//!   (22 parse rows + 17 end-to-end link rows locked for the next module).
//!
//! Scope honesty: this is a focused structural parser covering hierarchical
//! `scheme://authority/path?query#fragment` URIs with AOSP semantics for the
//! accessors parseAuthCallbackParameters uses (scheme, userInfo, host, port, path,
//! encodedQuery, encodedFragment). It is NOT a general android.net.Uri
//! replacement: opaque URIs, relative URIs and decoded-path segment APIs are out
//! of scope. Known divergence: Java's UTF-8 decoder REPLACE flush vs Rust
//! from_utf8_lossy can segment multi-byte malformed sequences differently; every
//! corpus row (single- and multi-byte) matches exactly.
//!
//! Empirical quirks locked by probe (do not "fix"):
//! - '%' followed by invalid hex consumes the bad char, appends U+FFFD, AND still
//!   buffers the partial hex byte (e.g. "%4Z" -> U+FFFD U+0004).
//! - '%' at end of input flushes, appends U+FFFD, drops the partial byte.
//! - Buffered bytes decode as UTF-8 with REPLACE; NUL (0x00) is valid UTF-8 and
//!   lands in the output.
//! - '+' is preserved (convertPlus=false); callers replace '+' with ' ' where
//!   production does (completeEmailLink error_description path).
//! - Empty port "host:" parses as port=-1, so `cyclecare://auth:/callback` passes
//!   the service's structural gate.
//! - Port overflow (e.g. 65536) parses fine and is then rejected by the port != -1
//!   check; non-digit ports leave host containing the colon and fail the host check.

const FFFD: char = '\u{FFFD}';

fn hex_char_to_value(c: char) -> i32 {
    match c {
        '0'..='9' => c as i32 - '0' as i32,
        'A'..='F' => c as i32 - 'A' as i32 + 10,
        'a'..='f' => c as i32 - 'a' as i32 + 10,
        _ => -1,
    }
}

/// AOSP UriCodec.decode with convertPlus=false, charset=UTF-8, throwOnFailure=false.
pub fn uri_decode(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let mut builder = String::with_capacity(s.len());
    let mut bytes: Vec<u8> = Vec::with_capacity(s.len());
    macro_rules! flush {
        () => {
            if !bytes.is_empty() {
                builder.push_str(&String::from_utf8_lossy(&bytes));
                bytes.clear();
            }
        };
    }
    let mut i = 0usize;
    while i < chars.len() {
        let c = chars[i];
        i += 1;
        match c {
            '+' => {
                flush!();
                builder.push('+');
            }
            '%' => {
                let mut hex_value: u32 = 0;
                let mut abort = false;
                for _ in 0..2 {
                    if i >= chars.len() {
                        flush!();
                        builder.push(FFFD);
                        return builder;
                    }
                    let hc = chars[i];
                    i += 1;
                    let digit = hex_char_to_value(hc);
                    if digit < 0 {
                        flush!();
                        builder.push(FFFD);
                        abort = true;
                        break;
                    }
                    hex_value = hex_value * 0x10 + digit as u32;
                }
                let _ = abort; // byte is pushed on both paths, mirroring AOSP control flow
                bytes.push(hex_value as u8);
            }
            _ => {
                flush!();
                builder.push(c);
            }
        }
    }
    flush!();
    builder
}

const NOT_FOUND: i64 = -1;

/// Structural subset of AOSP StringUri for hierarchical URIs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedUri {
    pub scheme: Option<String>,
    pub user_info: Option<String>,
    pub host: Option<String>,
    pub port: i64,
    pub path: String,
    pub encoded_query: Option<String>,
    pub encoded_fragment: Option<String>,
}

/// Reverse digit scan over the whole authority (AOSP findPortSeparator).
fn find_port_separator(authority: &[u8]) -> Option<usize> {
    let mut i = authority.len();
    while i > 0 {
        i -= 1;
        let c = authority[i];
        if c == b':' {
            return Some(i);
        }
        if !(b'0'..=b'9').contains(&c) {
            return None;
        }
    }
    None
}

/// Parse a hierarchical URI with AOSP StringUri semantics (scoped, see module doc).
pub fn parse_uri(s: &str) -> ParsedUri {
    let bytes = s.as_bytes();
    let ssi = s.find(':');
    let (scheme, rest_start) = match ssi {
        Some(i) => (Some(s[..i].to_string()), i + 1),
        None => (None, 0),
    };
    // Fragment separator is the FIRST '#'.
    let fsi = s.find('#');
    let body_end = fsi.unwrap_or(s.len());
    let encoded_fragment = fsi.map(|f| s[f + 1..].to_string());
    // Authority: "//" immediately after scheme separator.
    let (authority, path_start) = if scheme.is_some()
        && body_end > rest_start + 1
        && &s[rest_start..rest_start + 2] == "//"
    {
        let mut end = rest_start + 2;
        while end < body_end {
            match bytes[end] {
                b'/' | b'?' | b'#' => break,
                _ => end += 1,
            }
        }
        (Some(s[rest_start + 2..end].to_string()), end)
    } else {
        (None, rest_start)
    };
    // Path: until '?' or end of body.
    let qsi = s[path_start..body_end].find('?').map(|q| path_start + q);
    let path = match qsi {
        Some(q) => s[path_start..q].to_string(),
        None => s[path_start..body_end].to_string(),
    };
    let encoded_query = qsi.map(|q| s[q + 1..body_end].to_string());
    // Authority split: userInfo at LAST '@'; port per findPortSeparator over the
    // whole authority; host between.
    let (user_info, host, port) = match &authority {
        None => (None, None, NOT_FOUND),
        Some(auth) => {
            let ui_sep = auth.rfind('@');
            let port_sep = find_port_separator(auth.as_bytes());
            let host_start = ui_sep.map(|u| u + 1).unwrap_or(0);
            let host_end = port_sep.unwrap_or(auth.len());
            let host = uri_decode(&auth[host_start..host_end]);
            let user_info = ui_sep.map(|u| uri_decode(&auth[..u]));
            let port = match port_sep {
                None => NOT_FOUND,
                Some(p) => {
                    let port_string = uri_decode(&auth[p + 1..]);
                    port_string.parse::<i32>().map(|v| v as i64).unwrap_or(NOT_FOUND)
                }
            };
            (user_info, Some(host), port)
        }
    };
    ParsedUri {
        scheme,
        user_info,
        host,
        port,
        path,
        encoded_query,
        encoded_fragment,
    }
}

const ALLOWED_KEYS: [&str; 15] = [
    "code", "access_token", "refresh_token", "expires_at", "expires_in", "token_type", "type",
    "error", "error_description", "error_code", "error_uri", "provider_token",
    "provider_refresh_token", "state", "scope",
];

/// Verbatim port of SupabaseAuthService.parseAuthCallbackParameters (31b88f5).
/// Returns None for null; Some(vec) preserves insertion order like LinkedHashMap.
pub fn parse_auth_callback_parameters(uri: &ParsedUri) -> Option<Vec<(String, String)>> {
    if uri.scheme.as_deref() != Some("cyclecare")
        || uri.host.as_deref() != Some("auth")
        || uri.path != "/callback"
        || uri.user_info.is_some()
        || uri.port != -1
    {
        return None;
    }
    let mut result: Vec<(String, String)> = Vec::new();
    fn absorb(raw: Option<&str>, result: &mut Vec<(String, String)>) -> bool {
        let raw = match raw {
            Some(r) if !r.is_empty() => r,
            _ => return true,
        };
        for part in raw.split('&') {
            if part.is_empty() {
                return false;
            }
            // indexOf('=') is the first separator; value keeps the rest verbatim.
            let (key_raw, value_raw) = match part.find('=') {
                Some(sep) => (&part[..sep], &part[sep + 1..]),
                None => (part, ""),
            };
            let key = uri_decode(key_raw);
            let value = uri_decode(value_raw);
            if !ALLOWED_KEYS.contains(&key.as_str()) || result.iter().any(|(k, _)| *k == key) {
                return false;
            }
            result.push((key, value));
        }
        true
    }
    if !absorb(uri.encoded_query.as_deref(), &mut result)
        || !absorb(uri.encoded_fragment.as_deref(), &mut result)
    {
        return None;
    }
    let has = |k: &str| result.iter().any(|(key, _)| key == k);
    if has("code") && (has("access_token") || has("refresh_token")) {
        return None;
    }
    Some(result)
}

/// Convenience: parse a link string and extract callback parameters in one call.
pub fn parse_auth_callback_url(url: &str) -> Option<Vec<(String, String)>> {
    parse_auth_callback_parameters(&parse_uri(url))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn fixture(name: &str) -> String {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("fixtures/oracle/auth");
        p.push(name);
        std::fs::read_to_string(p).expect("fixture")
    }

    /// AOSP decode corpus: exact UTF-16 code-unit hex comparison.
    #[test]
    fn uri_decode_matches_aosp_corpus() {
        let mut checked = 0;
        for line in fixture("results_decode.tsv").lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            let input = cols.iter().find_map(|c| c.strip_prefix("in=")).unwrap();
            let expect_hex = cols.iter().find_map(|c| c.strip_prefix("hex=")).unwrap();
            if expect_hex == "-" {
                continue; // no throwing cases in this corpus
            }
            let got = uri_decode(input);
            let got_hex: String = got.chars().map(|c| format!("{:04X}", c as u32)).collect();
            assert_eq!(got_hex, expect_hex, "decode mismatch for {input:?}");
            checked += 1;
        }
        assert_eq!(checked, 15);
    }

    fn fmt_params(p: &Option<Vec<(String, String)>>) -> String {
        match p {
            None => "null".to_string(),
            Some(v) => v.iter().map(|(k, val)| format!("{k}:{val}")).collect::<Vec<_>>().join("|"),
        }
    }

    /// parseAuthCallbackParameters corpus (22 rows) executed against the verbatim
    /// Kotlin service with real AOSP Uri.
    #[test]
    fn callback_params_match_kotlin_corpus() {
        let mut checked = 0;
        for line in fixture("results_authcb.tsv").lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.get(1) != Some(&"parse") {
                continue;
            }
            let url = cols.iter().find_map(|c| c.strip_prefix("url=")).unwrap();
            let expect = cols.iter().find_map(|c| c.strip_prefix("result=")).unwrap();
            let got = fmt_params(&parse_auth_callback_url(url));
            assert_eq!(&got, expect, "params mismatch for {url}");
            checked += 1;
        }
        assert_eq!(checked, 22);
    }

    /// Structural corpus (14 rows): scheme/host/port/path + final parse verdict.
    #[test]
    fn structural_parse_matches_aosp_corpus() {
        let mut checked = 0;
        for line in fixture("results_struct.tsv").lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            let id = cols[0];
            let field = |prefix: &str| -> String {
                cols.iter()
                    .find_map(|c| c.strip_prefix(prefix))
                    .unwrap_or("")
                    .to_string()
            };
            // recover the url from the parse corpus row with the same id
            let url = fixture("results_authcb.tsv")
                .lines()
                .chain(fixture("results_authcb.tsv").lines())
                .find(|l| l.starts_with(&format!("{id}\t")))
                .and_then(|l| l.split('\t').find_map(|c| c.strip_prefix("url=")).map(|u| u.to_string()))
                .unwrap_or_else(|| match id {
                    "q01" => "cyclecare://auth:xx/callback#access_token=A".to_string(),
                    "q02" => "CYCLECARE://auth/callback#access_token=A".to_string(),
                    "q03" => "cyclecare://AUTH/callback#access_token=A".to_string(),
                    "q04" => "cyclecare://auth/callback/?x=1#access_token=A".to_string(),
                    "q05" => "cyclecare://auth//callback#access_token=A".to_string(),
                    "q06" => "cyclecare://a@b@auth/callback#access_token=A".to_string(),
                    "q07" => "cyclecare://auth/callback#access_token=A?code=C".to_string(),
                    "q08" => "cyclecare://auth/callback?code=C#access_token=A".to_string(),
                    "q09" => "cyclecare://auth/callback#type=RECOVERY".to_string(),
                    "q10" => "cyclecare://auth:65536/callback#access_token=A".to_string(),
                    "q11" => "cyclecare://auth:/callback#access_token=A".to_string(),
                    "q12" => "cyclecare://auth/callback#code=C#access_token=A".to_string(),
                    "q13" => "cyclecare://auth#access_token=A".to_string(),
                    "q14" => "cyclecare:///callback#access_token=A".to_string(),
                    other => panic!("unknown structural row {other}"),
                });
            let parsed = parse_uri(&url);
            assert_eq!(parsed.scheme.as_deref().unwrap_or("null"), field("scheme="), "{id} scheme");
            let expect_host = field("host=");
            assert_eq!(parsed.host.as_deref().unwrap_or("null"), expect_host, "{id} host");
            assert_eq!(parsed.port.to_string(), field("port="), "{id} port");
            assert_eq!(parsed.path, field("path="), "{id} path");
            let got = fmt_params(&parse_auth_callback_parameters(&parsed));
            assert_eq!(got, field("result="), "{id} result");
            checked += 1;
        }
        assert_eq!(checked, 14);
    }
}
