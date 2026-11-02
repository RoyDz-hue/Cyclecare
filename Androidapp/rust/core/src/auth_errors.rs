//! Port of data/supabase/AuthErrorMessages.kt (main@31b88f5): user-facing
//! auth error mapping. details = the non-blank optString values of
//! code/error_code/msg/message/error_description/error joined with spaces,
//! ifBlank -> the raw body, lowercased; ordered operation-specific matchers,
//! then per-operation fallback copy. AOSP optString coerces non-String values
//! via toString (numeric code 401 -> "401" -> no match -> fallback; e14).
//!
//! Locked by AuthErrParity (parity/auth, results_autherr.tsv, e01-e20 + 9
//! fallback rows) executed against the VERBATIM Kotlin with AOSP org.json.

use crate::kotlin_text::kotlin_is_blank;
use crate::sync_codec::{aosp_opt_string, JsonParser};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthOperation {
    SignUp,
    SignIn,
    MagicLink,
    PasswordReset,
    Guest,
    Callback,
    AccountLink,
    Google,
    SetPassword,
}

pub fn fallback(operation: AuthOperation) -> &'static str {
    match operation {
        AuthOperation::SignUp => "We couldn't create your account right now. Please try again later.",
        AuthOperation::SignIn => "We couldn't sign you in right now. Please try again later.",
        AuthOperation::MagicLink => "We couldn't send a sign-in link right now. Please try again later.",
        AuthOperation::PasswordReset => "We couldn't send password reset instructions right now. Please try again later.",
        AuthOperation::Guest => "Guest access is temporarily unavailable. Please try again later.",
        AuthOperation::Callback => "We couldn't complete sign-in from that link. Request a new link and try again.",
        AuthOperation::AccountLink => "We couldn't link your account right now. Please try again later.",
        AuthOperation::Google => "Google sign-in couldn't be completed. Please try again.",
        AuthOperation::SetPassword => "Your password couldn't be updated right now. Please try again.",
    }
}

fn details_for(body: &str) -> String {
    let parsed = JsonParser::new(body).parse().ok();
    let parts: Vec<String> = ["code", "error_code", "msg", "message", "error_description", "error"]
        .iter()
        .filter_map(|key| {
            parsed.as_ref().map(|p| aosp_opt_string(p, key, "")).filter(|v| !kotlin_is_blank(v))
        })
        .collect();
    let joined = parts.join(" ");
    (if joined.is_empty() { body.to_string() } else { joined }).to_lowercase()
}

pub fn for_response(body: &str, operation: AuthOperation) -> String {
    let details = details_for(body);
    let any = |needles: &[&str]| needles.iter().any(|n| details.contains(n));

    if operation == AuthOperation::SignIn
        && any(&["invalid login credentials", "invalid_credentials", "wrong password", "incorrect password"])
    {
        return "That email and password don't match. Check them and try again.".to_string();
    }
    if operation == AuthOperation::SignUp
        && any(&["already registered", "already exists", "user_exists", "email_exists"])
    {
        return "An account with that email already exists. Sign in or reset your password.".to_string();
    }
    if operation == AuthOperation::AccountLink
        && any(&["already registered", "already exists", "user_exists", "email_exists"])
    {
        return "That email is already linked to another account. Sign out, then sign in to that account.".to_string();
    }
    if operation == AuthOperation::SignUp
        && any(&["password should be at least", "password is too short", "weak_password"])
    {
        return "Choose a password with at least 8 characters.".to_string();
    }
    if operation == AuthOperation::Guest
        && any(&["anonymous sign-ins are disabled", "anonymous sign in is disabled", "anonymous users are disabled"])
    {
        return "Guest access is temporarily unavailable. Try again later or sign in with email.".to_string();
    }
    if operation == AuthOperation::AccountLink
        && any(&["manual_linking_disabled", "manual linking is disabled"])
    {
        return "Linking with Google is not available yet. Use Create Account with your email to keep this device's progress.".to_string();
    }
    if operation == AuthOperation::Callback && details.contains("access_denied") {
        return "Sign-in was cancelled. You can try again whenever you're ready.".to_string();
    }
    if operation == AuthOperation::Callback
        && any(&["expired", "invalid token", "invalid code", "already been used", "otp_expired"])
    {
        return "This CycleCare link has expired or was already used. Request a new link and try again.".to_string();
    }
    if (operation == AuthOperation::SignIn || operation == AuthOperation::Callback)
        && any(&["email not confirmed", "email_not_confirmed", "email is not confirmed"])
    {
        return "Please verify your email using the CycleCare link before signing in.".to_string();
    }
    fallback(operation).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_locked_to_jvm() {
        use AuthOperation::*;
        let cases: &[(AuthOperation, &str, &str)] = &[
            (SignIn, r#"{"msg":"Invalid login credentials"}"#, "That email and password don't match. Check them and try again."),
            (SignIn, r#"{"msg":"invalid_credentials"}"#, "That email and password don't match. Check them and try again."),
            (SignUp, r#"{"error":"user_exists"}"#, "An account with that email already exists. Sign in or reset your password."),
            (AccountLink, r#"{"message":"already registered"}"#, "That email is already linked to another account. Sign out, then sign in to that account."),
            (SignUp, r#"{"msg":"password should be at least 8 characters"}"#, "Choose a password with at least 8 characters."),
            (Guest, r#"{"msg":"Anonymous sign-ins are disabled"}"#, "Guest access is temporarily unavailable. Try again later or sign in with email."),
            (AccountLink, r#"{"error_code":"manual_linking_disabled"}"#, "Linking with Google is not available yet. Use Create Account with your email to keep this device's progress."),
            (Callback, r#"{"error":"access_denied"}"#, "Sign-in was cancelled. You can try again whenever you're ready."),
            (Callback, r#"{"error_description":"otp_expired"}"#, "This CycleCare link has expired or was already used. Request a new link and try again."),
            (SignIn, r#"{"msg":"email not confirmed"}"#, "Please verify your email using the CycleCare link before signing in."),
            (Callback, r#"{"msg":"email_not_confirmed"}"#, "Please verify your email using the CycleCare link before signing in."),
            (SignIn, "invalid login credentials", "That email and password don't match. Check them and try again."),
            (SignIn, r#"{"code":401}"#, "We couldn't sign you in right now. Please try again later."),
            (SignIn, r#"{"msg":"  "}"#, "We couldn't sign you in right now. Please try again later."),
            (SignIn, r#"{"msg":"invalid login credentials and expired"}"#, "That email and password don't match. Check them and try again."),
            (SignIn, r#"{"msg":"INVALID LOGIN CREDENTIALS"}"#, "That email and password don't match. Check them and try again."),
            (Callback, r#"{"msg":"token is expired"}"#, "This CycleCare link has expired or was already used. Request a new link and try again."),
            (SignUp, "not json at all", "We couldn't create your account right now. Please try again later."),
            (AccountLink, r#"{"msg":"manual linking is disabled"}"#, "Linking with Google is not available yet. Use Create Account with your email to keep this device's progress."),
        ];
        for (op, body, expected) in cases {
            assert_eq!(for_response(body, *op), *expected, "{op:?} {body}");
        }
        assert_eq!(fallback(SignUp), "We couldn't create your account right now. Please try again later.");
        assert_eq!(fallback(SignIn), "We couldn't sign you in right now. Please try again later.");
        assert_eq!(fallback(MagicLink), "We couldn't send a sign-in link right now. Please try again later.");
        assert_eq!(fallback(PasswordReset), "We couldn't send password reset instructions right now. Please try again later.");
        assert_eq!(fallback(Guest), "Guest access is temporarily unavailable. Please try again later.");
        assert_eq!(fallback(Callback), "We couldn't complete sign-in from that link. Request a new link and try again.");
        assert_eq!(fallback(AccountLink), "We couldn't link your account right now. Please try again later.");
        assert_eq!(fallback(Google), "Google sign-in couldn't be completed. Please try again.");
        assert_eq!(fallback(SetPassword), "Your password couldn't be updated right now. Please try again.");
    }
}
