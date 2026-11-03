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
//! authorities. IPv4 dotted-quad literals follow the JDK parser shape
//! (4 labels of 1-3 digits, range unchecked) - MODELED, pending
//! differential execution; no narrowing policy is introduced. IPv6
//! bracket literals are validated with a modeled scanIPv6Address shape
//! (pending differential; zone/scope ids not ported). Trailing-dot FQDN
//! hosts and empty ports follow the JDK17 differential evidence. Strings
//! with spaces/control chars fail parse exactly like URISyntaxException. Android's java.net.URI implementation
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
    // :21 toASCIIString() of a cleanly-parsed, all-ASCII URI equals the
    // raw input string (no re-encoding: scheme/authority/path are emitted
    // verbatim, case preserved, an EMPTY PORT keeps its colon - JDK17
    // probe: URI("https://host:").toASCIIString() == "https://host:").
    // removeSuffix("/") strips ONE trailing slash.
    let _ = ep;
    Some(value.strip_suffix('/').map(|v| v.to_string()).unwrap_or_else(|| value.to_string()))
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


/// JDK17-observed getHost() hostname shape: dot-separated labels of
/// ASCII alphanumerics, interior '-' allowed, no empty labels, top label
/// starts with a letter. Evidence: rejects my_host, exa!mple.com,
/// -bad.com, a..b, host.123, ho%73t.com, héllo.com.
fn jdk_server_host(host: &str) -> bool {
    // JDK matchIPv4Reference shape: exactly 4 labels of 1-3 ASCII digits,
    // octet RANGE unchecked by java.net.URI (InetAddress checks range, URI
    // does not). MODELED from the JDK parser, NOT yet differential-
    // executed by the reviewer - flagged for the next differential round.
    {
        let v4: Vec<&str> = host.split('.').collect();
        if v4.len() == 4
            && v4.iter().all(|l| !l.is_empty() && l.len() <= 3 && l.bytes().all(|b| b.is_ascii_digit()))
        {
            return true;
        }
    }
    // A single TRAILING dot (FQDN form, "host.") is accepted by Java -
    // JDK17 differential evidence. Drop it before label validation; an
    // empty label anywhere else (a..b) still fails.
    let host = host.strip_suffix('.').unwrap_or(host);
    if host.is_empty() {
        return false; // "." alone
    }
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


/// IPv6 literal validation, modeled on JDK scanIPv6Address: hex groups of
/// 1-4 digits, at most one "::" compression (required when fewer than 8
/// groups), an embedded dotted-IPv4 counts as two groups and must be the
/// last group. Pending reviewer differential execution.
fn valid_ipv6_literal(inner: &str) -> bool {
    if inner.is_empty() || inner.contains('%') {
        return false; // zone ids unported
    }
    let (left, right, compressed) = match inner.split_once("::") {
        Some((l, r)) => {
            if r.contains("::") {
                return false; // at most one "::"
            }
            (l, r, true)
        }
        None => (inner, "", false),
    };
    fn groups(side: &str) -> Option<(usize, bool)> {
        if side.is_empty() {
            return Some((0, false));
        }
        let gs: Vec<&str> = side.split(':').collect();
        let mut count = 0;
        for (i, g) in gs.iter().enumerate() {
            if g.is_empty() {
                return None; // single ':' producing an empty group
            }
            let is_last = i == gs.len() - 1;
            if g.contains('.') {
                // embedded IPv4: last group, 4 labels of 1-3 digits
                let v4: Vec<&str> = g.split('.').collect();
                if !is_last
                    || v4.len() != 4
                    || !v4.iter().all(|l| !l.is_empty() && l.len() <= 3 && l.bytes().all(|b| b.is_ascii_digit()))
                {
                    return None;
                }
                count += 2;
                return Some((count, true));
            }
            if g.len() > 4 || !g.bytes().all(|b| b.is_ascii_hexdigit()) {
                return None;
            }
            count += 1;
        }
        Some((count, false))
    }
    let (lc, lv4) = match groups(left) { Some(v) => v, None => return false };
    let (rc, rv4) = match groups(right) { Some(v) => v, None => return false };
    let total = lc + rc;
    if lv4 || rv4 {
        // IPv4 tail counts as 2 groups
        if compressed {
            total < 8
        } else {
            total == 8
        }
    } else if compressed {
        total < 8
    } else {
        total == 8
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
    // treat the authority as registry-based -> getHost() null. An EMPTY
    // port ("https://host:") is accepted by Java with port -1 (JDK17
    // differential evidence). Bracketed IPv6 literals are split at ']'
    // first so the inner colons are not read as a port.
    let (host, port) = if hostport.starts_with('[') {
        match hostport.find(']') {
            Some(close) => {
                let after = &hostport[close + 1..];
                let p = if after.is_empty() {
                    -1
                } else if let Some(p) = after.strip_prefix(':') {
                    if p.is_empty() {
                        -1
                    } else if p.chars().all(|c| c.is_ascii_digit()) {
                        match p.parse::<i32>() { Ok(n) => n, Err(_) => return None }
                    } else {
                        return None;
                    }
                } else {
                    return None; // junk after ']'
                };
                (&hostport[..close + 1], p)
            }
            None => return None, // unterminated '[' -> getHost null
        }
    } else {
        match hostport.rfind(':') {
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
        }
    };
    // java.net.URI getHost() server-based hostname validation, per JDK17
    // differential evidence: ASCII alphanum labels separated by dots, each
    // label starting AND ending alnum with interior '-' allowed, no empty
    // labels, and the top label must start with a letter. Underscores,
    // '!', '%' (even valid escapes), non-ASCII and anything else make
    // getHost() null. IPv4 literals take the JDK matchIPv4Reference path
    // and bracketed IPv6 literals a modeled scanIPv6Address path (both
    // pending differential execution).
    let host_ok = if host.starts_with('[') {
        // getHost() returns the brackets; inner must be a valid IPv6
        // literal. MODELED from JDK scanIPv6Address, pending differential;
        // zone/scope ids ('%') are NOT ported (unverified in JDK URI).
        host.ends_with(']') && valid_ipv6_literal(&host[1..host.len() - 1])
    } else {
        jdk_server_host(host)
    };
    if host.is_empty() || !host_ok {
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
