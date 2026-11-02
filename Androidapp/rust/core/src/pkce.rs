//! Rust port of SupabaseAuthService.beginPkceFlow (31b88f5:541-550).
//!
//! Production: 32 SecureRandom bytes -> Base64.URL_SAFE|NO_WRAP|NO_PADDING
//! verifier; challenge = Base64(SHA-256(verifier as US-ASCII)) with the same
//! flags; stores verifier + pending action on AuthPreferences.
//!
//! Grounding: k01 row in fixtures/oracle/auth/results_authcb.tsv was executed
//! against the verbatim service with AOSP android.util.Base64 (compiled from
//! android-14.0.0_r1) and proves verifier=43 b64url chars, action stored, and
//! challenge == b64url(sha256(verifier)). The encoder is additionally locked by
//! RFC 4648 vectors and the challenge path by the RFC 7636 Appendix B example.
//! Randomness is injected so tests are deterministic; the FFI/production side
//! supplies OS randomness.

use crate::auth_link::AuthPrefsState;
use sha2::{Digest, Sha256};

/// AOSP Base64.encodeToString with URL_SAFE | NO_WRAP | NO_PADDING:
/// url-safe alphabet, no padding, no newlines.
pub fn b64url_no_pad(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity((bytes.len() * 4 + 2) / 3);
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(ALPHABET[(n >> 6) as usize & 63] as char);
        }
        if chunk.len() > 2 {
            out.push(ALPHABET[n as usize & 63] as char);
        }
    }
    out
}

/// challenge = b64url-no-pad(SHA-256(verifier bytes as US-ASCII)).
pub fn pkce_challenge(verifier: &str) -> String {
    let digest = Sha256::digest(verifier.as_bytes());
    b64url_no_pad(&digest)
}

/// Verbatim beginPkceFlow semantics with injected randomness. Returns the
/// challenge; stores the verifier and pending action.
pub fn begin_pkce_flow(random32: [u8; 32], action: &str, prefs: &mut AuthPrefsState) -> String {
    let verifier = b64url_no_pad(&random32);
    let challenge = pkce_challenge(&verifier);
    prefs.google_pkce_verifier = Some(verifier);
    prefs.pending_auth_action = Some(action.to_string());
    challenge
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc4648_vectors() {
        assert_eq!(b64url_no_pad(b""), "");
        assert_eq!(b64url_no_pad(b"f"), "Zg");
        assert_eq!(b64url_no_pad(b"fo"), "Zm8");
        assert_eq!(b64url_no_pad(b"foo"), "Zm9v");
        assert_eq!(b64url_no_pad(b"foob"), "Zm9vYg");
        assert_eq!(b64url_no_pad(b"fooba"), "Zm9vYmE");
        assert_eq!(b64url_no_pad(b"foobar"), "Zm9vYmFy");
        assert_eq!(b64url_no_pad(&[0xFB, 0xFF, 0xFE]), "-__-");
    }

    #[test]
    fn rfc7636_appendix_b_challenge() {
        // Authoritative PKCE example from RFC 7636.
        assert_eq!(
            pkce_challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
    }

    #[test]
    fn begin_flow_shape_matches_k01() {
        // k01: verifier is 43 b64url chars, challenge == b64url(sha256(verifier)),
        // action stored. 32 bytes -> ceil(32/3)*4 - padding = 43 chars.
        let mut prefs = AuthPrefsState::fresh();
        let random = [0x42u8; 32];
        let challenge = begin_pkce_flow(random, "GOOGLE", &mut prefs);
        let verifier = prefs.google_pkce_verifier.as_deref().unwrap();
        assert_eq!(verifier.len(), 43);
        assert!(verifier
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        assert_eq!(challenge, pkce_challenge(verifier));
        assert_eq!(prefs.pending_auth_action.as_deref(), Some("GOOGLE"));
    }
}
