//! Port of domain/diagnostics/DebugBridgeConfiguration.kt (main@31b88f5,
//! 25 lines). The developer-origin gate: non-debug -> None, blank or >2048
//! UTF-16 units -> None, URI parse failure -> None, then structural checks
//! (https scheme, host, no userInfo/query/fragment, path in {"", "/"},
//! port -1 or 1..65535) and toASCIIString().removeSuffix("/").
//!
//! FAITHFUL SUBSET of java.net.URI: the parser here handles the
//! scheme://[userinfo@]host[:port][/][?q][#f] shape the configuration can
//! realistically carry. Exotic-but-legal URIs (IPv6 literals, IDN,
//! percent-encoded authorities, registry-based authorities) are NOT
//! ported; strings with spaces/control chars fail parse exactly like
//! URISyntaxException. The header comment's rule stands: an accepted
//! origin is never an authorization to collect diagnostics.

use crate::kotlin_text::kotlin_trim;

pub fn base_url(is_debug: bool, configured_url: &str) -> Option<String> {
    // :8
    if !is_debug {
        return None;
    }
    // :9-10 (Kotlin String.length = UTF-16 units)
    let value = kotlin_trim(configured_url);
    if value.is_empty() || value.encode_utf16().count() > 2048 {
        return None;
    }
    let ep = parse_uri_subset(value)?;
    // :15-20
    if !ep.scheme.eq_ignore_ascii_case("https")
        || ep.host.is_empty()
        || ep.user_info.is_some()
        || ep.has_query
        || ep.has_fragment
        || !(ep.path.is_empty() || ep.path == "/")
        || (ep.port != -1 && !(1..=65535).contains(&ep.port))
    {
        return None;
    }
    // :21 toASCIIString for this subset reconstructs the URI as written
    // (all-ASCII input required by the parser; case preserved, java.net.URI
    // does not normalize case), then removeSuffix("/") strips ONE slash.
    Some(ep.to_ascii_string().strip_suffix('/').map(|s| s.to_string()).unwrap_or_else(|| ep.to_ascii_string()))
}

struct UriSubset {
    scheme: String,
    user_info: Option<String>,
    host: String,
    port: i32,
    path: String,
    has_query: bool,
    has_fragment: bool,
}

impl UriSubset {
    /// java.net.URI.toASCIIString(): scheme://[userinfo@]host[:port]path[?q][#f]
    fn to_ascii_string(&self) -> String {
        let mut s = format!("{}://", self.scheme);
        if let Some(u) = &self.user_info {
            s.push_str(u);
            s.push('@');
        }
        s.push_str(&self.host);
        if self.port != -1 {
            s.push_str(&format!(":{}", self.port));
        }
        s.push_str(&self.path);
        s
    }
}

/// Returns None exactly where `new URI(value)` throws URISyntaxException or
/// yields a shape whose getHost() is null in the subset.
fn parse_uri_subset(value: &str) -> Option<UriSubset> {
    // java.net.URI rejects space/control characters anywhere.
    if value.chars().any(|c| (c as u32) <= 0x20) {
        return None;
    }
    // Scheme: [a-zA-Z][a-zA-Z0-9+.-]* ':' - required for a server-based
    // authority; without it getHost() is null (opaque URI).
    let colon = value.find(':')?;
    let scheme = &value[..colon];
    let mut ch = scheme.chars();
    if !ch.next()?.is_ascii_alphabetic()
        || !ch.all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.')
    {
        return None;
    }
    let rest = &value[colon + 1..];
    let rest = rest.strip_prefix("//")?; // no authority -> host null
    // Split authority / path / query / fragment.
    let (authority, after) = match rest.find(['/', '?', '#']) {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let (path, has_query, has_fragment) = {
        let (p, qf) = match after.find(['?', '#']) {
            Some(i) => (&after[..i], &after[i..]),
            None => (after, ""),
        };
        (p.to_string(), qf.starts_with('?'), qf.starts_with('#') || qf.contains('#'))
    };
    // user-info: up to the LAST '@' (RFC 2396 server-based authority).
    let (user_info, hostport) = match authority.rfind('@') {
        Some(i) => (Some(authority[..i].to_string()), &authority[i + 1..]),
        None => (None, authority),
    };
    // host[:port]; a non-numeric or out-of-int port makes java.net.URI
    // treat the authority as registry-based -> getHost() null.
    let (host, port) = match hostport.rfind(':') {
        Some(i) => {
            let p = &hostport[i + 1..];
            if p.is_empty() || !p.chars().all(|c| c.is_ascii_digit()) {
                return None; // registry-based -> getHost() null
            }
            match p.parse::<i32>() {
                Ok(n) => (&hostport[..i], n),
                Err(_) => return None, // over int range -> parse failure in getHost
            }
        }
        None => (hostport, -1),
    };
    if host.is_empty() {
        return None;
    }
    Some(UriSubset {
        scheme: scheme.to_string(),
        user_info,
        host: host.to_string(),
        port,
        path,
        has_query,
        has_fragment,
    })
}
