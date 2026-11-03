//! DebugBridgeConfiguration.kt decision pins. URI handling is a documented
//! faithful subset of java.net.URI (see module doc).
use cyclecare_core::debug_bridge::base_url;

#[test]
fn non_debug_never_returns_an_origin() {
    assert_eq!(base_url(false, "https://dev.example.com"), None, ":8");
}

#[test]
fn blank_or_oversize_returns_none() {
    assert_eq!(base_url(true, ""), None);
    assert_eq!(base_url(true, "   "), None, ":9 trim then empty");
    let long = format!("https://{}", "a".repeat(2050));
    assert_eq!(base_url(true, &long), None, ":10 >2048 UTF-16 units");
}

#[test]
fn scheme_and_structure_checks() {
    assert_eq!(base_url(true, "http://dev.example.com"), None, ":15 https only");
    assert_eq!(base_url(true, "HTTPS://dev.example.com"), Some("HTTPS://dev.example.com".into()), "scheme match ignoreCase; case preserved in output");
    assert_eq!(base_url(true, "https://user@dev.example.com"), None, ":17 userInfo rejected");
    assert_eq!(base_url(true, "https://dev.example.com/?x=1"), None, ":18 query rejected");
    assert_eq!(base_url(true, "https://dev.example.com/#f"), None, ":18 fragment rejected");
    assert_eq!(base_url(true, "https://dev.example.com/path"), None, ":19 path must be empty or /");
    assert_eq!(base_url(true, "https://dev.example.com/"), Some("https://dev.example.com".into()), ":21 removeSuffix strips the lone /");
}

#[test]
fn port_rules() {
    assert_eq!(base_url(true, "https://dev.example.com:8443"), Some("https://dev.example.com:8443".into()));
    assert_eq!(base_url(true, "https://dev.example.com:0"), None, ":20 port 0 out of range");
    assert_eq!(base_url(true, "https://dev.example.com:65535").is_some(), true);
    assert_eq!(base_url(true, "https://dev.example.com:65536"), None);
    assert_eq!(base_url(true, "https://dev.example.com:abc"), None, "non-numeric port -> getHost null");
}

#[test]
fn unparseable_returns_none() {
    assert_eq!(base_url(true, "not a url"), None, "URISyntaxException parity");
    assert_eq!(base_url(true, "dev.example.com"), None, "no scheme -> opaque -> host null");
    assert_eq!(base_url(true, "https://dev example.com"), None, "space rejected");
    assert_eq!(base_url(true, "https://"), None, "empty host");
    // 2048 units exactly is allowed (the check is >).
    let exact = format!("https://{}", "a".repeat(2041)); // 8 + 2041 = 2049? no: "https://" is 8
    let _ = exact;
    let fits = format!("https://{}", "a".repeat(2040));
    assert!(base_url(true, &fits).is_some(), "2048 units exactly passes");
}

#[test]
fn jdk17_differential_boundary() {
    // Each case from the executed JDK17 differential: Java getHost() null
    // (or the shape rejected) -> None here too.
    for bad in [
        "https://my_host",
        "https://exa!mple.com",
        "https://-bad.com",
        "https://a..b",
        "https://host.123",
        "https://ho%73t.com",
        "https://héllo.com",
    ] {
        assert_eq!(base_url(true, bad), None, "JDK17 differential false-accept: {bad}");
    }
    // Empty port: Java ACCEPTS with port -1 AND preserves the colon in
    // toASCIIString (reviewer JDK17 probe output: "https://host:").
    assert_eq!(base_url(true, "https://host:"), Some("https://host:".into()));
    // Still-accepted neighbors that must not regress.
    assert_eq!(base_url(true, "https://a-b.example-1.com"), Some("https://a-b.example-1.com".into()));
    assert_eq!(base_url(true, "https://bad-.com"), None, "label ending in dash");
    // IPv4 literals follow the JDK parser shape (4 labels, 1-3 digits,
    // range unchecked) - modeled, pending differential execution.
    assert_eq!(base_url(true, "https://1.2.3.4"), Some("https://1.2.3.4".into()));
    assert_eq!(base_url(true, "https://999.1.1.1"), Some("https://999.1.1.1".into()), "URI does not range-check octets (InetAddress would)");
    assert_eq!(base_url(true, "https://1.2.3.4567"), None, "4-digit label fails IPv4 shape and the alpha-top-label rule");
    assert_eq!(base_url(true, "https://1.2.3"), None, "3 numeric labels: not IPv4, top label not alpha-starting");
}

#[test]
fn jdk17_differential_round3() {
    // Trailing colon retained after removeSuffix (v76 shape, pinned again):
    assert_eq!(base_url(true, "https://host:/"), Some("https://host:".into()), "host:/ strips slash, keeps colon");
    // Trailing dot FQDN form - Java accepts (differential evidence).
    assert_eq!(base_url(true, "https://host."), Some("https://host.".into()));
    assert_eq!(base_url(true, "https://host.:80"), Some("https://host.:80".into()));
    assert_eq!(base_url(true, "https://host.:"), Some("https://host.:".into()), "trailing dot + empty port");
    assert_eq!(base_url(true, "https://."), None, "bare dot: no labels");
    assert_eq!(base_url(true, "https://a..b."), None, "interior empty label still fails with trailing dot");
    // IPv6 literals - v74 accepted, v75 regressed, Java accepts [::1]:8443.
    assert_eq!(base_url(true, "https://[::1]:8443"), Some("https://[::1]:8443".into()));
    assert_eq!(base_url(true, "https://[::1]"), Some("https://[::1]".into()));
    assert_eq!(base_url(true, "https://[2001:db8:85a3:0:0:8a2e:370:7334]"), Some("https://[2001:db8:85a3:0:0:8a2e:370:7334]".into()));
    assert_eq!(base_url(true, "https://[2001:db8::192.0.2.1]"), Some("https://[2001:db8::192.0.2.1]".into()), "embedded IPv4");
    assert_eq!(base_url(true, "https://[:::]"), None, "empty group is not a literal");
    assert_eq!(base_url(true, "https://[1:2:3:4:5:6:7:8:9]"), None, "9 groups uncompressed");
    assert_eq!(base_url(true, "https://[1:2:3:4:5:6:7]"), None, "7 groups without :: compression");
    assert_eq!(base_url(true, "https://[gggg::1]"), None, "non-hex group");
    assert_eq!(base_url(true, "https://[::1]x"), None, "junk after bracket");
    assert_eq!(base_url(true, "https://[::1"), None, "unterminated bracket");
    // IPv4 with trailing dot: not IPv4-shaped (5th empty label), hostname
    // rule then sees top label "4" numeric -> reject.
    assert_eq!(base_url(true, "https://1.2.3.4."), None);
}
