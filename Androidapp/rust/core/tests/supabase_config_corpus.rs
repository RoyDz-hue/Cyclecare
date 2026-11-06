//! JVM-executed tables (ConfigProbe/ConfigProbe2 against compiled AOSP
//! Base64 + JSONObject classes, 2026-11-03).
use cyclecare_core::supabase_config::*;

const PUB20: &str = "sb_publishable_12345678901234567890";
// {"role":"anon","ref":"abc"} / {"role":"service_role","ref":"abc"} / {"role":"anon"}
const JWT_ABC: &str = "eyJh.eyJyb2xlIjoiYW5vbiIsInJlZiI6ImFiYyJ9.c2ln";
const JWT_SERVICE: &str = "eyJh.eyJyb2xlIjoic2VydmljZV9yb2xlIiwicmVmIjoiYWJjIn0.c2ln";
const JWT_NOREF: &str = "eyJh.eyJyb2xlIjoiYW5vbiJ9.c2ln";

#[test]
fn parse_public_api_key_table() {
    assert_eq!(parse_public_api_key(None), None);
    assert_eq!(parse_public_api_key(Some("")), None);
    assert_eq!(parse_public_api_key(Some("  ")), None);
    assert_eq!(parse_public_api_key(Some(DEFAULT_PUBLISHABLE_KEY)).as_deref(), Some(DEFAULT_PUBLISHABLE_KEY));
    assert_eq!(parse_public_api_key(Some("sb_publishable_short")), None);
    assert_eq!(parse_public_api_key(Some(PUB20)).as_deref(), Some(PUB20));
    assert_eq!(parse_public_api_key(Some("SB_SECRET_abc")), None);
    assert_eq!(parse_public_api_key(Some("service_role_xyz")), None);
    assert_eq!(parse_public_api_key(Some("my_replace_key")), None);
    assert_eq!(parse_public_api_key(Some("your-key")), None);
    assert_eq!(parse_public_api_key(Some("jwt_here")), None);
    assert_eq!(parse_public_api_key(Some(JWT_ABC)).as_deref(), Some(JWT_ABC));
    assert_eq!(parse_public_api_key(Some(JWT_SERVICE)), None);
    assert_eq!(parse_public_api_key(Some("eyJh. e30=.c")), None);
    assert_eq!(parse_public_api_key(Some("eyJh..c")), None);
    assert_eq!(parse_public_api_key(Some(&format!("x{}", JWT_ABC))), None);
}

#[test]
fn parse_endpoint_table() {
    assert_eq!(parse_endpoint(""), None);
    assert_eq!(parse_endpoint("http://localhost:54321").as_deref(), Some("http://localhost:54321"));
    assert_eq!(parse_endpoint("HTTP://LOCALHOST:54321").as_deref(), Some("http://localhost:54321"));
    assert_eq!(parse_endpoint("http://10.0.2.2:54321").as_deref(), Some("http://10.0.2.2:54321"));
    assert_eq!(parse_endpoint("https://ABC-def.supabase.co").as_deref(), Some("https://abc-def.supabase.co"));
    assert_eq!(parse_endpoint("https://abc.supabase.co/"), None); // no trailing-slash strip at this layer
    assert_eq!(parse_endpoint("https://abc.supabase.co.evil.com"), None);
    assert_eq!(parse_endpoint("http://abc.supabase.co"), None);
    assert_eq!(parse_endpoint("https://.supabase.co"), None);
    assert_eq!(parse_endpoint("https://a_b.supabase.co"), None);
}

#[test]
fn template_placeholders() {
    for yes in ["your_project_ref", "YOUR-PROJECT-REF", "replace_with_x", "placeholder", "insert_here"] {
        assert!(is_template_placeholder(yes), "{yes}");
    }
    assert!(!is_template_placeholder("real.supabase.co"));
}

#[test]
fn resolve_chain_executed_rows() {
    let defaults = resolve_client_settings(None, None);
    assert_eq!(defaults.url, DEFAULT_URL);
    assert_eq!(defaults.api_key, DEFAULT_PUBLISHABLE_KEY);
    assert!(defaults.is_configured());
    // Template url + template-LOOKING key: "your_anon_key" is NOT a template
    // under isTemplatePlaceholder (executed) but fails parse_public_api_key
    // -> DISABLED, not defaults.
    let r = resolve_client_settings(Some("your_project_ref"), Some("your_anon_key"));
    assert!(!r.is_configured());
    assert!(!resolve_client_settings(Some("https://abc.supabase.co"), None).is_configured());
    assert!(!resolve_client_settings(None, Some(PUB20)).is_configured());
    let r = resolve_client_settings(Some("http://127.0.0.1:54321"), Some(PUB20));
    assert_eq!(r.url, "http://127.0.0.1:54321");
    assert_eq!(r.api_key, PUB20);
    assert!(!resolve_client_settings(Some("https://abc.supabase.co"), Some(DEFAULT_PUBLISHABLE_KEY)).is_configured());
    assert_eq!(resolve_client_settings(Some("https://abc.supabase.co"), Some(PUB20)).url, "https://abc.supabase.co");
    assert_eq!(resolve_client_settings(Some("https://abc.supabase.co"), Some(JWT_ABC)).url, "https://abc.supabase.co");
    assert!(!resolve_client_settings(Some("https://xyz.supabase.co"), Some(JWT_ABC)).is_configured());
    assert_eq!(resolve_client_settings(Some("https://xyz.supabase.co"), Some(JWT_NOREF)).url, "https://xyz.supabase.co");
    assert_eq!(resolve_client_settings(Some("https://abc.supabase.co/"), Some(PUB20)).url, "https://abc.supabase.co");
    // Case-SENSITIVE scheme: "HTTPS://" fails parseEndpoint -> disabled.
    assert!(!resolve_client_settings(Some("HTTPS://ABC.supabase.co"), Some(PUB20)).is_configured());
    assert!(!resolve_client_settings(Some("   "), Some(PUB20)).is_configured());
    // Padded legacy payload decodes (NO_WRAP, padding allowed).
    let padded = "eyJh.eyJyb2xlIjoiYW5vbiJ9.c2ln";
    assert!(resolve_client_settings(Some("https://xyz.supabase.co"), Some(padded)).is_configured());
    // resolvePublicApiKey falls back to the public default.
    assert_eq!(resolve_public_api_key(Some("garbage")), DEFAULT_PUBLISHABLE_KEY);
    assert_eq!(resolve_client_settings(Some("https://abc.supabase.co"), Some(PUB20)).rest_url(), "https://abc.supabase.co/rest/v1");
}
