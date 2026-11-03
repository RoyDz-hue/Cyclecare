//! Port of domain/diagnostics/DebugBridgeConfiguration.kt (main@31b88f5,
//! 25 lines). The developer-origin gate: non-debug -> None, blank or >2048
//! UTF-16 units -> None, URI parse failure -> None, then structural checks
//! (https scheme, host, no userInfo/query/fragment, path in {"", "/"},
//! port -1 or 1..65535) and toASCIIString().removeSuffix("/").
//!
//! URI handling was tightened against an executed JDK17 differential
//! (getHost() server-authority rules: ASCII alnum labels, interior dashes,
//! no empty labels, alpha-starting top label; empty port accepted as -1).
//! REMAINING JDK-vs-Android/SUBSET LIMITATIONS: exotic-but-legal URIs are
//! NOT ported - IPv6 literals, IDN, percent-encoded and registry-based
//! authorities; IPv4 dotted-quad literals are a NARROWING deviation
//! (Java accepts them, this parser rejects them - a debug gate failing
//! closed, never open). Strings with spaces/control chars fail parse
//! exactly like URISyntaxException. Android's java.net.URI implementation
//! vs JDK17 was not re-differentialed. The header comment's rule stands:
//! an accepted origin is never an authorization to collect diagnostics.

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


/// JDK17-observed getHost() hostname shape: dot-separated labels of
/// ASCII alphanumerics, interior '-' allowed, no empty labels, top label
/// starts with a letter. Evidence: rejects my_host, exa!mple.com,
/// -bad.com, a..b, host.123, ho%73t.com, héllo.com.
fn jdk_server_host(host: &str) -> bool {
    let labels: Vec<&str> = host.split('.').collect();
    for (i, label) in labels.iter().enumerate() {
        if label.is_empty() {
            return false; // a..b
        }
        let bytes = label.as_bytes();
        if !bytes[0].is_ascii_alphanumeric() || !bytes[bytes.len() - 1].is_ascii_alphanumeric() {
            return false; // -bad.com / bad-.com
        }
        if !bytes.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'-') {
            return false; // my_host, exa!mple, ho%73t, héllo (non-ASCII bytes)
        }
        if i == labels.len() - 1 && !bytes[0].is_ascii_alphabetic() {
            return false; // host.123 - top label must start alpha
        }
    }
    true
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
    // treat the authority as registry-based -> getHost() null. An EMPTY
    // port ("https://host:") is accepted by Java with port -1 (JDK17
    // differential evidence).
    let (host, port) = match hostport.rfind(':') {
        Some(i) => {
            let p = &hostport[i + 1..];
            if p.is_empty() {
                (&hostport[..i], -1)
            } else if !p.chars().all(|c| c.is_ascii_digit()) {
                return None; // registry-based -> getHost() null
            } else {
                match p.parse::<i32>() {
                    Ok(n) => (&hostport[..i], n),
                    Err(_) => return None, // over int range -> parse failure in getHost
                }
            }
        }
        None => (hostport, -1),
    };
    // java.net.URI getHost() server-based hostname validation, per JDK17
    // differential evidence: ASCII alphanum labels separated by dots, each
    // label starting AND ending alnum with interior '-' allowed, no empty
    // labels, and the top label must start with a letter. Underscores,
    // '!', '%' (even valid escapes), non-ASCII and anything else make
    // getHost() null. NARROWING deviations (reject where Java accepts),
    // all labeled: IPv4 dotted-quad literals (Java accepts
    // "1.2.3.4"-shaped hosts; this subset rejects them because the top
    // label is numeric) and IPv6 literals (not ported at all).
    if host.is_empty() || !jdk_server_host(host) {
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
