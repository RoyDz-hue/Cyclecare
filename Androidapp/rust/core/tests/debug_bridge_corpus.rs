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
    // Empty port: Java ACCEPTS with port -1 (differential evidence).
    assert_eq!(base_url(true, "https://host:"), Some("https://host".into()));
    // Still-accepted neighbors that must not regress.
    assert_eq!(base_url(true, "https://a-b.example-1.com"), Some("https://a-b.example-1.com".into()));
    assert_eq!(base_url(true, "https://bad-.com"), None, "label ending in dash");
    // Narrowing deviation, documented on the module: IPv4 literals are
    // REJECTED here although Java accepts them.
    assert_eq!(base_url(true, "https://1.2.3.4"), None, "labeled narrowing: IPv4 literal rejected");
}
