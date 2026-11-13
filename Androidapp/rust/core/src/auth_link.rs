//! Rust port of the SupabaseAuthService email-link / auth-code completion tree
//! (completeEmailLink, completeAuthCode, saveSession, emailIsConfirmed, readUser,
//! operationForAction, successMessageForAction, clearPendingAuthFlow) at 31b88f5.
//!
//! Grounding: the verbatim Kotlin service was executed on the JVM against AOSP
//! android.net.Uri/UriCodec (compiled from android-14.0.0_r1 source), AOSP
//! android.util.Base64, AOSP org.json and a programmable okhttp stub; 16
//! end-to-end rows (c01-c16 in fixtures/oracle/auth/results_authcb.tsv) lock the
//! observable outcomes: result class + message, session writes, request count.
//!
//! AuthPreferences state model mirrors the REAL AuthPreferences (security/
//! AuthPreferences.kt), executed on the JVM through its unitTest seam
//! (fixtures/oracle/auth/results_prefs.tsv, rows s01-s03). SCOPE OF THAT
//! EVIDENCE: the seam substitutes an in-memory SharedPreferences, so what was
//! executed is the real getter/setter/generation/guard LOGIC over a plaintext
//! map - NOT EncryptedSharedPreferences, NOT Keystore, NOT any encryption
//! behavior. Encrypted production storage remains unverified host glue.
//! Also note: writeSecureString REMOVES the key on insecure stores (s03);
//! that is current-source semantics and a safety concern (silent credential
//! loss), NOT an accepted target fallback for the migration.
//! Locked semantics: accountMode is a
//! non-null String defaulting to "ANONYMOUS"; the userId setter bumps
//! session_generation_v3 (Math.addExact) whenever the value changes;
//! clearSession clears tokens/expiry/pkce/user/pending fields, sets
//! accountMode="ANONYMOUS" and bumps the generation; insecure stores drop
//! credential writes (writeSecureString removes the key instead).
//! The full link corpus (c01-c20) was re-executed against the REAL
//! AuthPreferences class - result classes and messages are identical to the
//! stub run; the only field-level difference was the store-default
//! accountMode on rows that never write it, which this model now mirrors.
//!
//! Transport and clock are injected seams, mirroring PaymentUseCase's
//! constructor-injected seams (learning: test at the seams production owns).
//! Network exception -> catch-all -> operation-appropriate copy, never a
//! bad-credentials message, never an attempt consumed.

use crate::auth_errors::{fallback, for_response, AuthOperation};
use crate::auth_uri::parse_auth_callback_url;
use crate::kotlin_text::{kotlin_equals_ignore_case, kotlin_is_blank};
use crate::sync_codec::{aosp_opt_string, json_value_to_string, JsonParser, JsonValue};

pub const AUTH_ACTION_GUEST_LINK: &str = "GUEST_LINK";
pub const AUTH_ACTION_PASSWORD_RESET: &str = "PASSWORD_RESET";
pub const AUTH_ACTION_GOOGLE_LINK: &str = "GOOGLE_LINK";
pub const AUTH_ACTION_GOOGLE: &str = "GOOGLE";
pub const AUTH_ACTION_SIGN_UP: &str = "SIGN_UP";
pub const AUTH_ACTION_MAGIC_LINK: &str = "MAGIC_LINK";

const MSG_INVALID_LINK: &str = "This is not a valid CycleCare sign-in link.";
const MSG_CONFLICTING: &str = "This CycleCare sign-in link contains conflicting session data. Request a new link.";
const MSG_UNCONFIRMED: &str = "Email verification is not complete yet. Open the newest link from your inbox.";
const MSG_DIFFERENT_ACCOUNT: &str =
    "This link belongs to a different account. Request a new CycleCare link on this device.";
const MSG_VERIFY_FAILED: &str =
    "We couldn't verify this CycleCare link right now. Check your connection and try again.";
const MSG_ALREADY_SIGNED_IN: &str =
    "An account is already signed in on this device. Sign out before switching accounts.";
const MSG_VERIFY_EMAIL_FIRST: &str =
    "Please verify your email using the CycleCare link before continuing.";
const MSG_SECURE_UNAVAILABLE: &str = "Secure credential storage is unavailable on this device. Sign-in, cloud sync, and app lock are disabled until device security is restored.";

/// Kotlin String.ifBlank { null }. The blank predicate is the SHARED
/// probe-locked kotlin_text::kotlin_is_blank (TrimProbe2's executed set);
/// this module's older private copy diverged at U+0085 (Kotlin: NOT
/// whitespace) and U+001C-U+001F (Kotlin: whitespace) and was removed
/// (one-implementation reconciliation, 2026-11-13).
fn if_blank(s: String) -> Option<String> {
    if kotlin_is_blank(&s) {
        None
    } else {
        Some(s)
    }
}

// ---------- AOSP JSONObject opt helpers not already in sync_codec ----------

fn obj_get<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj {
        JsonValue::Object(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

/// AOSP optJSONObject: the value only when it is a JSONObject, else null.
fn opt_object<'a>(obj: &'a JsonValue, key: &str) -> Option<&'a JsonValue> {
    match obj_get(obj, key) {
        Some(v @ JsonValue::Object(_)) => Some(v),
        _ => None,
    }
}

/// AOSP optBoolean: Boolean -> value; String -> Boolean.parseBoolean
/// ("true" ignoring ASCII case); anything else (incl. numbers) -> fallback.
fn opt_boolean(obj: &JsonValue, key: &str, fallback: bool) -> bool {
    match obj_get(obj, key) {
        Some(JsonValue::Bool(b)) => *b,
        Some(JsonValue::Str(s)) => s.eq_ignore_ascii_case("true"),
        _ => fallback,
    }
}

/// AOSP org.json JSON.toLong's String branch (android14-release libcore,
/// org/json/JSON.java): `(long) Double.parseDouble(value)` - the FULL Java
/// double grammar, NOT Long.parseDouble-free Long.parseLong: String.trim()
/// (chars <= U+0020) whitespace, optional sign, case-sensitive
/// NaN/Infinity, decimal floats with optional exponent, hex floats
/// (0x..p..), and an optional d/f/D/F type suffix; then the JVM
/// double-to-long cast (NaN -> 0, +/-Infinity and overflow saturate,
/// truncate toward zero - exactly Rust's float `as` cast). None =
/// NumberFormatException, i.e. the point where optLong hits its default
/// branch. Mantissa accumulation is f64 (exact to 2^53); longer literals
/// are a drift/attack-only edge and saturate on the cast anyway.
fn aosp_parse_double_to_long(s: &str) -> Option<i64> {
    let t = s.trim_matches(|c: char| (c as u32) <= 0x20);
    let (t, neg) = match t.strip_prefix('-') {
        Some(r) => (r, true),
        None => (t.strip_prefix('+').unwrap_or(t), false),
    };
    if t == "NaN" {
        return Some(0);
    }
    if t == "Infinity" {
        return Some(if neg { i64::MIN } else { i64::MAX });
    }
    let core = match t.strip_suffix(|c| matches!(c, 'd' | 'D' | 'f' | 'F')) {
        Some(r) => r,
        None => t,
    };
    if core.is_empty() {
        return None;
    }
    let d = if let Some(hex) = core.strip_prefix("0x").or_else(|| core.strip_prefix("0X")) {
        parse_java_hex_float(hex)?
    } else {
        parse_java_decimal_float(core)?
    };
    Some((if neg { -d } else { d }) as i64)
}

/// Java DecimalFloatingPointLiteral (digits before and/or after an optional
/// dot, optional decimal exponent); validated by hand, then Rust-parsed
/// (the validated grammar is a subset of Rust's f64 grammar).
fn parse_java_decimal_float(core: &str) -> Option<f64> {
    let b = core.as_bytes();
    let digits = |b: &[u8], mut i: usize| -> usize {
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        i
    };
    let mut i = 0;
    let int_len = digits(b, 0);
    i = int_len;
    let mut frac_len = 0usize;
    if i < b.len() && b[i] == b'.' {
        i += 1;
        let start = i;
        i = digits(b, i);
        frac_len = i - start;
    }
    if int_len == 0 && frac_len == 0 {
        return None;
    }
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        i += 1;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            i += 1;
        }
        let start = i;
        i = digits(b, i);
        if i == start {
            return None;
        }
    }
    if i != b.len() {
        return None;
    }
    core.parse::<f64>().ok()
}

/// Exact power-of-two scaling with i64 exponent arithmetic (the JVM
/// double-to-long cast makes any subnormal-or-smaller result collapse to
/// 0, so clamping far outside the f64 range is exact for every
/// cast-visible outcome).
fn scale_pow2(mant: f64, e: i64) -> f64 {
    if mant == 0.0 {
        return 0.0;
    }
    if e > 1100 {
        return f64::INFINITY;
    }
    if e < -1200 {
        return 0.0;
    }
    mant * 2f64.powi(e as i32)
}

/// Java HexFloatingPointLiteral body (after 0x): hex mantissa with optional
/// fraction and a REQUIRED binary (p/P) exponent - bare "0xff" is not a
/// hex float literal. EXACT correctly-rounded conversion: the mantissa is
/// kept as its first <=64 significant bits in a u64 window plus a sticky
/// flag, the exponent is combined in i64 (a p-exponent of -2147483647 with
/// a fraction UNDERFLOWED the previous i32 combination and wrapped to
/// Infinity - regression-pinned), and the mantissa is rounded to 53 bits
/// with round-half-to-even on exact guard/sticky bits, then scaled by an
/// exact power of two. For a mantissa of <=53 significant bits the scaling
/// multiply is the single, correctly-rounded rounding of the true value
/// whenever the power-of-two factor is itself representable (scale exponent
/// >= -1074: normals AND subnormals). Below that the factor underflows and
/// the returned float can differ from Double.parseDouble by a subnormal
/// ulp - e.g. "0x1.8p-1075" yields 0.0 where Java yields Double.MIN_VALUE.
/// For longer mantissas the one theoretical double-rounding band is
/// likewise deep-subnormal. Every value in both bands has |v| < 1 and the
/// JVM (long) cast maps it to 0 regardless - so no cast-visible divergence
/// from Double.parseDouble remains.
fn parse_java_hex_float(hex: &str) -> Option<f64> {
    let b = hex.as_bytes();
    let hexd = |b: &[u8], mut i: usize| -> usize {
        while i < b.len() && b[i].is_ascii_hexdigit() {
            i += 1;
        }
        i
    };
    // Collect significant bits (MSB first) from int+frac digit runs.
    let mut window: u64 = 0; // first min(L,64) significant bits
    let mut bits: i64 = 0; // L: significant bit count
    let mut sticky = false; // any dropped 1 bit beyond the window
    let mut seen = false;
    let mut absorb = |digits: &str, window: &mut u64, bits: &mut i64, sticky: &mut bool, seen: &mut bool| {
        for c in digits.chars() {
            let d = c.to_digit(16).unwrap_or(0);
            for shift in [3i8, 2, 1, 0] {
                let bit = (d >> shift) & 1;
                if !*seen {
                    if bit == 1 {
                        *seen = true;
                        *window = 1;
                        *bits = 1;
                    }
                    continue;
                }
                if *bits < 64 {
                    *window = (*window << 1) | bit as u64;
                    *bits += 1;
                } else {
                    *sticky |= bit == 1;
                    *bits += 1;
                }
            }
        }
    };
    let int_end = hexd(b, 0);
    let int_len = int_end;
    absorb(&hex[..int_end], &mut window, &mut bits, &mut sticky, &mut seen);
    let mut i = int_end;
    let mut frac_len: i64 = 0;
    if i < b.len() && b[i] == b'.' {
        i += 1;
        let start = i;
        i = hexd(b, i);
        frac_len = (i - start) as i64;
        absorb(&hex[start..i], &mut window, &mut bits, &mut sticky, &mut seen);
    }
    if int_len == 0 && frac_len == 0 {
        return None;
    }
    if i >= b.len() || (b[i] != b'p' && b[i] != b'P') {
        return None;
    }
    i += 1;
    let exp_neg = if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        let n = b[i] == b'-';
        i += 1;
        n
    } else {
        false
    };
    let start = i;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
    }
    if i == start || i != b.len() {
        return None;
    }
    // Java accumulates arbitrary exponent digits; beyond i64 the result is
    // simply Infinity/0 (the mantissa is nonzero at this point iff seen).
    let p_exp: i64 = match hex[start..i].parse() {
        Ok(v) => if exp_neg { -v } else { v },
        Err(_) => {
            return Some(if !seen || exp_neg { 0.0 } else { f64::INFINITY });
        }
    };
    if !seen {
        return Some(0.0); // all-zero mantissa: 0x0p-anything == 0
    }
    // True value: window-represented mantissa M (L significant bits) x
    // 2^K, K in i64 - no wrap possible.
    let k = p_exp - 4 * frac_len;
    let (rounded, e) = if bits <= 53 {
        (window, k)
    } else {
        // Round M to 53 bits, round-half-to-even, on exact guard/sticky.
        let sh = bits - 54; // value bits below the top 54
        let dropped = if bits > 64 { bits - 64 } else { 0 }; // bits outside the window
        let w = window >> (sh - dropped); // top 54 significant bits
        let guard = (w & 1) == 1;
        // Window bits below the guard: positions 0..sh-dropped-1 (>= 0 always:
        // sh-dropped == bits-54 when bits <= 64, == 10 when bits > 64).
        let below = sh - dropped;
        let sticky = sticky || (window & ((1u64 << below) - 1)) != 0;
        let mut r = w >> 1; // 53 bits
        let mut e = k + sh + 1;
        if guard && (sticky || (r & 1) == 1) {
            r += 1;
            if r == (1u64 << 53) {
                r >>= 1;
                e += 1;
            }
        }
        (r, e)
    };
    Some(scale_pow2(rounded as f64, e))
}

/// AOSP optLong: Number -> longValue (Double truncated toward zero);
/// String -> (long) Double.parseDouble (see aosp_parse_double_to_long -
/// full Java grammar, junk hits the default); else fallback.
fn opt_long(obj: &JsonValue, key: &str, fallback: i64) -> i64 {
    match obj_get(obj, key) {
        Some(JsonValue::Int(v)) => *v as i64,
        Some(JsonValue::Long(v)) => *v,
        Some(JsonValue::Double(v)) => v.trunc() as i64,
        Some(JsonValue::Str(s)) => aosp_parse_double_to_long(s).unwrap_or(fallback),
        _ => fallback,
    }
}

// ---------- session preference state ----------

/// AuthPreferences fields the link/code tree reads or writes, with real
/// clearSession semantics (see module doc).
#[derive(Debug, Clone, Default)]
pub struct AuthPrefsState {
    pub is_secure_storage_available: bool,
    /// Real getter default is "ANONYMOUS" (prefs.getString(KEY_ACCOUNT_MODE,
    /// "ANONYMOUS") ?: "ANONYMOUS"); modeled as Some("ANONYMOUS") from fresh().
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub session_expires_at_seconds: i64,
    pub user_id: Option<String>,
    pub user_email: Option<String>,
    pub account_mode: Option<String>,
    pub pending_email: Option<String>,
    pub pending_auth_action: Option<String>,
    pub google_pkce_verifier: Option<String>,
    pub is_pending_guest_email_link: bool,
    pub needs_password_setup: bool,
    pub session_generation: i64,
    /// deviceId (AuthPreferences.kt:342-350): get-or-generate-and-persist;
    /// generation is host glue, modeled via FlowSeams.new_device_id.
    pub device_id: Option<String>,
}

impl AuthPrefsState {
    pub fn fresh() -> Self {
        AuthPrefsState {
            is_secure_storage_available: true,
            session_generation: 0,
            account_mode: Some("ANONYMOUS".to_string()),
            ..Default::default()
        }
    }

    /// Real userId setter: bumps session_generation_v3 (checked add) when the
    /// value changes, then writeSecureString (insecure store removes the key).
    pub fn set_user_id(&mut self, value: Option<String>) {
        let normalized = if !self.is_secure_storage_available { None } else { value };
        if self.user_id != normalized {
            self.session_generation = self
                .session_generation
                .checked_add(1)
                .expect("session generation overflow");
        }
        self.user_id = normalized;
    }

    /// Real AuthPreferences.clearSession: drops tokens/user/pending state,
    /// sets accountMode back to "ANONYMOUS", bumps the generation (checked).
    pub fn clear_session(&mut self) {
        self.session_generation = self
            .session_generation
            .checked_add(1)
            .expect("session generation overflow");
        self.access_token = None;
        self.refresh_token = None;
        self.session_expires_at_seconds = 0;
        self.google_pkce_verifier = None;
        self.user_id = None;
        self.user_email = None;
        self.pending_email = None;
        self.pending_auth_action = None;
        self.is_pending_guest_email_link = false;
        self.needs_password_setup = false;
        self.account_mode = Some("ANONYMOUS".to_string());
    }

    /// pub for the sibling flows module (same service, split files).
    pub fn clear_pending_auth_flow(&mut self, clear_pending_email: bool) {
        self.google_pkce_verifier = None;
        self.pending_auth_action = None;
        if clear_pending_email {
            self.pending_email = None;
            self.is_pending_guest_email_link = false;
        }
    }
}

// ---------- results ----------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthUser {
    pub id: String,
    pub email: Option<String>,
    pub email_confirmed_at: Option<String>,
    pub is_anonymous: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthOutcome {
    Success { user: AuthUser, message: String },
    VerificationRequired(String),
    Error(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponse {
    pub successful: bool,
    pub body: String,
    /// Raw Location header when present (createGoogleLinkUrl falls back to it).
    pub header_location: Option<String>,
}

/// Injected transport: method, url, optional JSON body. Err = network failure
/// (the production catch-all path).
pub type Transport<'a> = dyn FnMut(&str, &str, Option<&str>) -> Result<HttpResponse, String> + 'a;

// ---------- pure helpers (verbatim ports) ----------

pub fn email_is_confirmed(user: &JsonValue) -> bool {
    // AOSP optString turns an explicit JSON null into the literal "null",
    // which is NOT blank; mirrored by aosp_opt_string.
    // CURRENT-SOURCE PARITY ONLY (rows c17/c18): an explicit null
    // email_confirmed_at is accepted as confirmed. That is what the Kotlin
    // source does today; it is NOT an accepted security design and NOT
    // authorization to rely on it for actual account linking. Flagged for
    // migration safety review; do not tighten or loosen without that review.
    !kotlin_is_blank(&aosp_opt_string(user, "email_confirmed_at", ""))
        || !kotlin_is_blank(&aosp_opt_string(user, "confirmed_at", ""))
}

pub fn read_user(json: &JsonValue, fallback_email: Option<&str>) -> AuthUser {
    let email = if_blank(aosp_opt_string(json, "email", ""))
        .or_else(|| fallback_email.map(|e| e.to_string()));
    let email_confirmed_at = if_blank(aosp_opt_string(json, "email_confirmed_at", ""))
        .or_else(|| if_blank(aosp_opt_string(json, "confirmed_at", "")));
    AuthUser {
        id: aosp_opt_string(json, "id", ""),
        email,
        email_confirmed_at,
        is_anonymous: opt_boolean(json, "is_anonymous", false),
    }
}

fn operation_for_action(action: &str) -> AuthOperation {
    match action {
        AUTH_ACTION_SIGN_UP => AuthOperation::SignUp,
        AUTH_ACTION_MAGIC_LINK => AuthOperation::MagicLink,
        AUTH_ACTION_PASSWORD_RESET => AuthOperation::PasswordReset,
        AUTH_ACTION_GUEST_LINK | AUTH_ACTION_GOOGLE_LINK => AuthOperation::AccountLink,
        _ => AuthOperation::Google,
    }
}

fn success_message_for_action(action: &str) -> &'static str {
    match action {
        AUTH_ACTION_SIGN_UP => "Your email is verified and your CycleCare account is ready.",
        AUTH_ACTION_MAGIC_LINK => "You're signed in to CycleCare.",
        AUTH_ACTION_PASSWORD_RESET => "Your reset link is verified. Set a new password to finish.",
        AUTH_ACTION_GUEST_LINK => {
            "Your guest data is now linked to your verified email. Set a password or use email sign-in."
        }
        AUTH_ACTION_GOOGLE_LINK => {
            "Your Google account is linked. Your guest progress stays with this account."
        }
        _ => "Your Google account is verified and signed in to CycleCare.",
    }
}

pub fn save_session(prefs: &mut AuthPrefsState, json: &JsonValue, mode: &str, now_seconds: i64) {
    let user = opt_object(json, "user").unwrap_or(json);
    prefs.account_mode = Some(mode.to_string());
    let uid = aosp_opt_string(user, "id", "");
    let assigned = if_blank(uid).or_else(|| prefs.user_id.clone());
    prefs.set_user_id(assigned);
    prefs.user_email = if_blank(aosp_opt_string(user, "email", ""));
    prefs.access_token = if_blank(aosp_opt_string(json, "access_token", ""));
    prefs.refresh_token = if_blank(aosp_opt_string(json, "refresh_token", ""));
    let expires_in = opt_long(json, "expires_in", 3600);
    prefs.session_expires_at_seconds = opt_long(json, "expires_at", now_seconds + expires_in);
}

// ---------- wiring seam: non-clock response interpretation ----------

/// Raw session fields extracted from an auth response (NEW IMPLEMENTATION
/// 2026-11-13, wiring seam). Write semantics of the source's saveSession:
/// user_id None = KEEP the current value (`.ifBlank { authPreferences.userId }`);
/// user_email/access_token/refresh_token None = SET null (`.ifBlank { null }`);
/// expires_at None = the source's default branch (now + expires_in) fires
/// AT THE CALLER'S COMMIT POINT - the clock read stays there, never here.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionFields {
    pub mode: String,
    pub user_id: Option<String>,
    pub user_email: Option<String>,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub expires_at: Option<i64>,
    pub expires_in: i64,
}

/// org.json optLong where hitting the default branch is the signal:
/// Number -> longValue (Double truncated toward zero), String ->
/// (long) Double.parseDouble (see aosp_parse_double_to_long - full Java
/// grammar, junk -> None), anything else -> None.
fn opt_long_raw(obj: &JsonValue, key: &str) -> Option<i64> {
    match obj_get(obj, key) {
        Some(JsonValue::Int(v)) => Some(*v as i64),
        Some(JsonValue::Long(v)) => Some(*v),
        Some(JsonValue::Double(v)) => Some(*v as i64),
        Some(JsonValue::Str(s)) => aosp_parse_double_to_long(s),
        _ => None,
    }
}

/// The field extraction of the source's saveSession, without the writes.
/// NOTE the user fallback: saveSession uses `optJSONObject("user") ?: json`
/// (the WHOLE body when no user object), NOT the empty object the
/// confirmation check / readUser use.
pub fn extract_session_fields(json: &JsonValue, mode: &str) -> SessionFields {
    let user = opt_object(json, "user").unwrap_or(json);
    SessionFields {
        mode: mode.to_string(),
        user_id: if_blank(aosp_opt_string(user, "id", "")),
        user_email: if_blank(aosp_opt_string(user, "email", "")),
        access_token: if_blank(aosp_opt_string(json, "access_token", "")),
        refresh_token: if_blank(aosp_opt_string(json, "refresh_token", "")),
        expires_at: opt_long_raw(json, "expires_at"),
        expires_in: opt_long(json, "expires_in", 3600),
    }
}

/// saveSession's writes, from extracted fields. PRECONDITION matching the
/// flows' guard: secure storage is available (set_user_id force-clears on
/// unavailable storage, which only the flows' early-exit makes equivalent).
pub fn apply_session_fields(prefs: &mut AuthPrefsState, f: &SessionFields, now_seconds: i64) {
    prefs.account_mode = Some(f.mode.clone());
    if let Some(uid) = &f.user_id {
        prefs.set_user_id(Some(uid.clone()));
    }
    prefs.user_email = f.user_email.clone();
    prefs.access_token = f.access_token.clone();
    prefs.refresh_token = f.refresh_token.clone();
    prefs.session_expires_at_seconds = f.expires_at.unwrap_or(now_seconds + f.expires_in);
}

/// signIn response decision (NEW IMPLEMENTATION 2026-11-13, wiring seam).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignInInterpretation {
    /// Error body mapping (auth_errors corpus) or the source catch-all's
    /// connect message on parse failure.
    Error(String),
    /// Unconfirmed email: the caller commits clearSession() and shows this.
    VerificationRequired(String),
    Success { user: AuthUser, message: String, session: SessionFields },
}

/// The NON-CLOCK interpretation of SupabaseAuthService.signIn's response:
/// status/error mapping, JSONObject parse, confirmation check, readUser,
/// and saveSession's RAW field extraction. clearSession/session WRITES and
/// the expiry clock read stay at the caller's original commit points.
/// `JSONObject(body)` throws on non-object text (the source's catch-all);
/// mirrored by requiring an Object here.
pub fn interpret_sign_in_response(
    successful: bool,
    body: &str,
    normalized_email: &str,
) -> SignInInterpretation {
    if !successful {
        return SignInInterpretation::Error(crate::auth_errors::for_response(
            body,
            crate::auth_errors::AuthOperation::SignIn,
        ));
    }
    let json = match JsonParser::new(body).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => {
            return SignInInterpretation::Error(
                "Unable to connect. Please check your network connection.".to_string(),
            )
        }
    };
    let empty = JsonValue::Object(vec![]);
    let user = opt_object(&json, "user").unwrap_or(&empty);
    if !email_is_confirmed(user) {
        return SignInInterpretation::VerificationRequired(
            "Please verify your email using the CycleCare link before signing in.".to_string(),
        );
    }
    SignInInterpretation::Success {
        user: read_user(user, Some(normalized_email)),
        message: "Successfully signed in to CycleCare.".to_string(),
        session: extract_session_fields(&json, "EMAIL"),
    }
}

/// signUp response decision (NEW IMPLEMENTATION 2026-11-13, wiring seam).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignUpInterpretation {
    /// Error body mapping: the caller commits clearPendingAuthFlow(true).
    ErrorClearPending(String),
    /// Parse catch-all: NO commit on this path in the source (the
    /// JSONObject(body) throw skips the pending-flow cleanup).
    ErrorPlain(String),
    /// Unverified or token-less response: the caller commits pendingEmail +
    /// pendingAuthAction and shows this.
    VerificationRequired(String),
    /// Verified with token: the caller commits clearPendingAuthFlow(true)
    /// plus the session writes.
    Success { user: AuthUser, message: String, session: SessionFields },
}

/// The NON-CLOCK interpretation of SupabaseAuthService.signUp's response:
/// status/error mapping (corpus), JSONObject-equivalent parse, whole-body
/// user fallback (UNLIKE signIn's empty object), confirmation check, the
/// accessToken.isNotBlank() && confirmed split, readUser, and saveSession's
/// RAW field extraction. All WRITES and the expiry clock read stay at the
/// caller's original commit points. A 2xx NON-OBJECT body yields the
/// source catch-all (JSONObject(body) throws on it) - an edge the f-rows
/// corpus does NOT lock, mirrored Kotlin-true.
pub fn interpret_sign_up_response(
    successful: bool,
    body: &str,
    normalized_email: &str,
) -> SignUpInterpretation {
    if !successful {
        return SignUpInterpretation::ErrorClearPending(crate::auth_errors::for_response(
            body,
            crate::auth_errors::AuthOperation::SignUp,
        ));
    }
    let json = match JsonParser::new(body).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => {
            return SignUpInterpretation::ErrorPlain(
                "We couldn't create your account right now. Check your connection and try again."
                    .to_string(),
            )
        }
    };
    // Source: json.optJSONObject("user") ?: json  (WHOLE body, not empty).
    let user = opt_object(&json, "user").unwrap_or(&json);
    let confirmed = email_is_confirmed(user);
    // Source: json.optString("access_token") then isNotBlank(); the
    // corpus-locked port used isEmpty - the whitespace-only-token edge is
    // NOT corpus-locked, and isBlank is what the Kotlin source does.
    let access_token = aosp_opt_string(&json, "access_token", "");
    if !kotlin_is_blank(&access_token) && confirmed {
        SignUpInterpretation::Success {
            user: read_user(user, Some(normalized_email)),
            message: "Your verified CycleCare account is ready.".to_string(),
            session: extract_session_fields(&json, "EMAIL"),
        }
    } else {
        SignUpInterpretation::VerificationRequired(format!(
            "Check {normalized_email} for your CycleCare verification link. The app will open automatically after you verify; you can enter only then."
        ))
    }
}

/// continueAsGuest response decision (NEW IMPLEMENTATION 2026-11-13, wiring
/// seam). NO commit happens on ANY guest error path in the source.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GuestInterpretation {
    Error(String),
    /// The caller commits the saveSession-equivalent writes and then
    /// userEmail = null, and builds SupabaseUser(id = userId, isAnonymous =
    /// true) itself.
    Success { user_id: String, message: String, session: SessionFields },
}

/// The NON-CLOCK interpretation of continueAsGuest's signup response:
/// status/error mapping (corpus), JSONObject-equivalent parse, whole-body
/// user fallback, the userId.isBlank() || accessToken.isBlank() -> corpus
/// fallback split (isBlank, not the port's isEmpty - whitespace-only is an
/// unlocked edge, mirrored Kotlin-true), and saveSession's RAW extraction
/// in ANONYMOUS mode.
pub fn interpret_guest_response(successful: bool, body: &str) -> GuestInterpretation {
    if !successful {
        return GuestInterpretation::Error(crate::auth_errors::for_response(
            body,
            crate::auth_errors::AuthOperation::Guest,
        ));
    }
    let json = match JsonParser::new(body).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => {
            return GuestInterpretation::Error(
                "A secure guest session needs an internet connection. Please retry when online."
                    .to_string(),
            )
        }
    };
    let user = opt_object(&json, "user").unwrap_or(&json);
    let user_id = aosp_opt_string(user, "id", "");
    let access_token = aosp_opt_string(&json, "access_token", "");
    if kotlin_is_blank(&user_id) || kotlin_is_blank(&access_token) {
        return GuestInterpretation::Error(
            crate::auth_errors::fallback(crate::auth_errors::AuthOperation::Guest).to_string(),
        );
    }
    GuestInterpretation::Success {
        user_id,
        message: "Your private guest session is ready.".to_string(),
        session: extract_session_fields(&json, "ANONYMOUS"),
    }
}

/// setPassword response decision (NEW IMPLEMENTATION 2026-11-13, wiring
/// seam).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SetPasswordInterpretation {
    Error(String),
    /// The caller commits needsPasswordSetup = false, then succeeds.
    Success { user: AuthUser, message: String },
}

/// The NON-CLOCK interpretation of setPassword's response: status/error
/// mapping (corpus) and readUser over runCatching { JSONObject(body) }
/// .getOrElse { JSONObject() } - parse failure AND non-object text both
/// yield the EMPTY object here (different from every other flow).
pub fn interpret_set_password_response(
    successful: bool,
    body: &str,
    fallback_email: &str,
) -> SetPasswordInterpretation {
    if !successful {
        return SetPasswordInterpretation::Error(crate::auth_errors::for_response(
            body,
            crate::auth_errors::AuthOperation::SetPassword,
        ));
    }
    let user = match JsonParser::new(body).parse() {
        Ok(v @ JsonValue::Object(_)) => v,
        _ => JsonValue::Object(vec![]),
    };
    SetPasswordInterpretation::Success {
        user: read_user(&user, Some(fallback_email)),
        message: "Your password is set.".to_string(),
    }
}

/// sendMagicLink / sendPasswordReset / linkGuestAccount response decision
/// (NEW IMPLEMENTATION 2026-11-13, wiring seam): the only NON-CLOCK content
/// is the status split and the corpus error-body mapping; every commit and
/// every success message stays with the caller (they differ per flow).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FlowStatusInterpretation {
    /// The caller commits its clearPendingAuthFlow variant, then shows this.
    Error(String),
    /// The caller commits its pending-flow writes and builds its message.
    Proceed,
}

pub fn interpret_flow_status(
    successful: bool,
    body: &str,
    operation: crate::auth_errors::AuthOperation,
) -> FlowStatusInterpretation {
    if successful {
        FlowStatusInterpretation::Proceed
    } else {
        FlowStatusInterpretation::Error(crate::auth_errors::for_response(body, operation))
    }
}

// ---------- wiring seams: auth callback (link/code) interpretation ----------

/// completeEmailLink's PRE-network parameter interpretation (NEW
/// IMPLEMENTATION wiring seam, 2026-11-13). NON-CLOCK, no commits: the
/// source branches on the parsed callback parameter map only. The
/// parseAuthCallbackParameters call itself (android.net.Uri semantics)
/// stays Kotlin; `params` is its ordered key/value output.
/// NOTE the blank checks are isBlank, NOT isEmpty (a whitespace-only
/// token/code takes the blank branch) - the older full-port tree used
/// is_empty there; corpus-unlocked edge, mirrored Kotlin-true here.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CbLinkParamsDecision {
    /// AuthResult.Error(message), no commit.
    Error(String),
    /// Delegate to completeAuthCode with this code.
    Code(String),
    /// Proceed to the /user request carrying the raw link fields (expiry
    /// values stay STRINGS: the commit converts with toLongOrNull at its
    /// own original clock point).
    Tokens {
        access_token: String,
        refresh_token: String,
        expires_at: Option<String>,
        expires_in: Option<String>,
        link_type: Option<String>,
    },
}

pub fn interpret_cb_link_params(params: &[(String, String)]) -> CbLinkParamsDecision {
    let value = |key: &str| -> Option<&str> {
        params
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    };
    // linkError = value("error_description") ?: value("error"): a PRESENT
    // but blank error_description does NOT fall through to "error".
    if let Some(e) = value("error_description").or_else(|| value("error")) {
        if !kotlin_is_blank(e) {
            return CbLinkParamsDecision::Error(for_response(
                &e.replace('+', " "),
                AuthOperation::Callback,
            ));
        }
    }
    let auth_code = value("code").unwrap_or("");
    if !kotlin_is_blank(auth_code) {
        if value("access_token").is_some() || value("refresh_token").is_some() {
            return CbLinkParamsDecision::Error(MSG_CONFLICTING.to_string());
        }
        return CbLinkParamsDecision::Code(auth_code.to_string());
    }
    let access_token = value("access_token").unwrap_or("");
    let refresh_token = value("refresh_token").unwrap_or("");
    if kotlin_is_blank(access_token) || kotlin_is_blank(refresh_token) {
        return CbLinkParamsDecision::Error(for_response("expired link", AuthOperation::Callback));
    }
    CbLinkParamsDecision::Tokens {
        access_token: access_token.to_string(),
        refresh_token: refresh_token.to_string(),
        expires_at: value("expires_at").map(|s| s.to_string()),
        expires_in: value("expires_in").map(|s| s.to_string()),
        link_type: value("type").map(|s| s.to_string()),
    }
}

/// completeEmailLink's POST-network /user response interpretation (NEW
/// IMPLEMENTATION wiring seam, 2026-11-13). NON-CLOCK: the commit (with
/// its expiry clock read) stays at the call site. The prefs-derived inputs
/// are read by the caller at the source's original read points (before the
/// commit that clears them). `link_type` is the raw "type" link parameter.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CbLinkUserSuccess {
    pub user_id: String,
    /// user email ifBlank pendingEmail.orEmpty() - may itself be blank; the
    /// commit maps blank -> null (source: `email.ifBlank { null }`).
    pub email: String,
    pub is_anonymous: bool,
    pub linked_guest: bool,
    pub password_recovery: bool,
    pub needs_password_setup: bool,
    pub account_mode: String,
    pub message: String,
    pub user: AuthUser,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CbLinkUserDecision {
    Error(String),
    Success(CbLinkUserSuccess),
}

pub fn interpret_cb_link_user(
    successful: bool,
    user_body: &str,
    is_pending_guest_email_link: bool,
    current_user_id: Option<&str>,
    pending_email: Option<&str>,
    pending_auth_action: Option<&str>,
    link_type: Option<&str>,
) -> CbLinkUserDecision {
    if !successful {
        return CbLinkUserDecision::Error(for_response(user_body, AuthOperation::Callback));
    }
    // JSONObject(userBody) throws on unparseable text -> source catch-all.
    let user = match JsonParser::new(user_body).parse() {
        Ok(v) if matches!(v, JsonValue::Object(_)) => v,
        _ => return CbLinkUserDecision::Error(MSG_VERIFY_FAILED.to_string()),
    };
    let user_id = aosp_opt_string(&user, "id", "");
    if kotlin_is_blank(&user_id) {
        return CbLinkUserDecision::Error(fallback(AuthOperation::Callback).to_string());
    }
    let is_anonymous = opt_boolean(&user, "is_anonymous", false);
    if !is_anonymous && !email_is_confirmed(&user) {
        return CbLinkUserDecision::Error(MSG_UNCONFIRMED.to_string());
    }
    let current_nonblank = current_user_id.map(|u| !kotlin_is_blank(u)).unwrap_or(false);
    if is_pending_guest_email_link && current_nonblank && current_user_id != Some(user_id.as_str()) {
        return CbLinkUserDecision::Error(MSG_DIFFERENT_ACCOUNT.to_string());
    }
    let linked_guest = is_pending_guest_email_link && !is_anonymous;
    let password_recovery = link_type
        .map(|t| kotlin_equals_ignore_case(t, "recovery"))
        .unwrap_or(false)
        || pending_auth_action == Some(AUTH_ACTION_PASSWORD_RESET);
    let email = if_blank(aosp_opt_string(&user, "email", ""))
        .or_else(|| pending_email.map(|e| e.to_string()))
        .unwrap_or_default();
    let message = if linked_guest {
        success_message_for_action(AUTH_ACTION_GUEST_LINK).to_string()
    } else if password_recovery {
        success_message_for_action(AUTH_ACTION_PASSWORD_RESET).to_string()
    } else {
        "Your CycleCare email is verified and you are signed in.".to_string()
    };
    CbLinkUserDecision::Success(CbLinkUserSuccess {
        user_id: user_id.clone(),
        user: read_user(&user, Some(email.as_str())),
        email,
        is_anonymous,
        linked_guest,
        password_recovery,
        needs_password_setup: linked_guest || password_recovery,
        account_mode: if is_anonymous { "ANONYMOUS" } else { "EMAIL" }.to_string(),
        message,
    })
}

/// completeAuthCode's PRE-network branch (NEW IMPLEMENTATION wiring seam,
/// 2026-11-13): verifier presence, the pendingAuthAction default,
/// linkingGuest and the already-signed-in gate. NON-CLOCK.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CbCodePreDecision {
    /// Error(message), no commit (verifier missing -> "expired code").
    Error(String),
    /// Commit clearPendingAuthFlow() (no email clear), then Error(message)
    /// (already signed in).
    ErrorClearFlow(String),
    /// Proceed to the PKCE token exchange with this resolved action.
    Ok { action: String, linking_guest: bool },
}

pub fn interpret_cb_code_pre(
    has_verifier: bool,
    pending_auth_action: Option<&str>,
    is_pending_guest_email_link: bool,
    existing_user_id: Option<&str>,
) -> CbCodePreDecision {
    if !has_verifier {
        return CbCodePreDecision::Error(for_response("expired code", AuthOperation::Callback));
    }
    let action = pending_auth_action
        .map(|a| a.to_string())
        .unwrap_or_else(|| {
            if is_pending_guest_email_link {
                AUTH_ACTION_GUEST_LINK.to_string()
            } else {
                AUTH_ACTION_GOOGLE.to_string()
            }
        });
    let linking_guest = (action == AUTH_ACTION_GUEST_LINK && is_pending_guest_email_link)
        || action == AUTH_ACTION_GOOGLE_LINK;
    let existing_nonblank = existing_user_id.map(|u| !kotlin_is_blank(u)).unwrap_or(false);
    if existing_nonblank && !linking_guest {
        return CbCodePreDecision::ErrorClearFlow(MSG_ALREADY_SIGNED_IN.to_string());
    }
    CbCodePreDecision::Ok { action, linking_guest }
}

/// completeAuthCode's POST-network token-response interpretation (NEW
/// IMPLEMENTATION wiring seam, 2026-11-13). NON-CLOCK: the commits stay at
/// the call site; saveSession's expiry default's clock read happens there.
/// `action`/`linking_guest` come from interpret_cb_code_pre (or its twin);
/// `existing_user_id`/`pending_email` are read at the source's points.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CbCodeResponseDecision {
    /// Commit clearPendingAuthFlow(clearPendingEmail = true), then
    /// Error(message).
    ErrorClear(String),
    /// Error(message) with NO commit: the source's parse catch-all
    /// (JSONObject(body) throws inside the try).
    ErrorPlain(String),
    Ok {
        /// saveSession(json, "EMAIL")'s raw field extraction (whole-body
        /// user fallback; expires_at None = optLong default branch, caller
        /// computes now+expires_in at the commit's clock point).
        session: SessionFields,
        /// user email ifBlank pendingEmail.orEmpty(); read pre-commit.
        email: String,
        needs_password_setup: bool,
        message: String,
        user: AuthUser,
    },
}

pub fn interpret_cb_code_response(
    action: &str,
    linking_guest: bool,
    existing_user_id: Option<&str>,
    pending_email: Option<&str>,
    successful: bool,
    body: &str,
) -> CbCodeResponseDecision {
    let operation = operation_for_action(action);
    if !successful {
        return CbCodeResponseDecision::ErrorClear(for_response(body, operation));
    }
    let json = match JsonParser::new(body).parse() {
        Ok(v) if matches!(v, JsonValue::Object(_)) => v,
        _ => return CbCodeResponseDecision::ErrorPlain(fallback(operation).to_string()),
    };
    let empty = JsonValue::Object(vec![]);
    let user = opt_object(&json, "user").unwrap_or(&empty);
    let user_id = aosp_opt_string(user, "id", "");
    if kotlin_is_blank(&aosp_opt_string(&json, "access_token", "")) || kotlin_is_blank(&user_id) {
        return CbCodeResponseDecision::ErrorClear(fallback(operation).to_string());
    }
    let existing_nonblank = existing_user_id.map(|u| !kotlin_is_blank(u)).unwrap_or(false);
    if linking_guest && existing_nonblank && existing_user_id != Some(user_id.as_str()) {
        // A link callback must upgrade THIS account, never switch to another one.
        return CbCodeResponseDecision::ErrorClear(MSG_DIFFERENT_ACCOUNT.to_string());
    }
    if !email_is_confirmed(user) {
        return CbCodeResponseDecision::ErrorClear(MSG_VERIFY_EMAIL_FIRST.to_string());
    }
    let email = if_blank(aosp_opt_string(user, "email", ""))
        .or_else(|| pending_email.map(|e| e.to_string()))
        .unwrap_or_default();
    CbCodeResponseDecision::Ok {
        session: extract_session_fields(&json, "EMAIL"),
        user: read_user(user, Some(email.as_str())),
        email,
        needs_password_setup: action == AUTH_ACTION_GUEST_LINK || action == AUTH_ACTION_PASSWORD_RESET,
        message: success_message_for_action(action).to_string(),
    }
}

// ---------- decision trees ----------

/// Verbatim port of completeEmailLink. `auth_api_url` mirrors the service's
/// injected base URL; the transport receives the exact GET the service builds.
/// ONE IMPLEMENTATION: every branch decision comes from the wiring seams
/// (interpret_cb_*); this tree owns only the transport and the commits
/// (with the expiry clock read at its own original commit point).
pub fn complete_email_link(
    prefs: &mut AuthPrefsState,
    url: &str,
    auth_api_url: &str,
    now_seconds: i64,
    transport: &mut Transport,
) -> AuthOutcome {
    if !prefs.is_secure_storage_available {
        return AuthOutcome::Error(MSG_SECURE_UNAVAILABLE.to_string());
    }
    let parameters = match parse_auth_callback_url(url) {
        Some(p) => p,
        None => return AuthOutcome::Error(MSG_INVALID_LINK.to_string()),
    };
    let (access_token, refresh_token, expires_at_raw, expires_in_raw, link_type) =
        match interpret_cb_link_params(&parameters) {
            CbLinkParamsDecision::Error(m) => return AuthOutcome::Error(m),
            CbLinkParamsDecision::Code(code) => {
                return complete_auth_code(prefs, &code, auth_api_url, now_seconds, transport);
            }
            CbLinkParamsDecision::Tokens { access_token, refresh_token, expires_at, expires_in, link_type } => {
                (access_token, refresh_token, expires_at, expires_in, link_type)
            }
        };

    // try { ... } catch -> "couldn't verify" copy
    let user_url = format!("{auth_api_url}/user");
    let response = match transport("GET", &user_url, None) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(MSG_VERIFY_FAILED.to_string()),
    };
    let su = match interpret_cb_link_user(
        response.successful,
        &response.body,
        prefs.is_pending_guest_email_link,
        prefs.user_id.as_deref(),
        prefs.pending_email.as_deref(),
        prefs.pending_auth_action.as_deref(),
        link_type.as_deref(),
    ) {
        CbLinkUserDecision::Error(m) => return AuthOutcome::Error(m),
        CbLinkUserDecision::Success(su) => su,
    };

    // The commit, in the source's write order. The expiry default's clock
    // read is the injected now_seconds - the tree's original commit point.
    // The expiry strings convert with toLongOrNull semantics (the source's
    // grammar here, NOT optLong); the userEmail write is ifBlank -> null.
    prefs.access_token = Some(access_token);
    prefs.refresh_token = Some(refresh_token);
    prefs.session_expires_at_seconds = expires_at_raw
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or_else(|| {
            now_seconds + expires_in_raw.and_then(|v| v.parse::<i64>().ok()).unwrap_or(3600)
        });
    prefs.set_user_id(Some(su.user_id));
    prefs.user_email = if kotlin_is_blank(&su.email) { None } else { Some(su.email.clone()) };
    prefs.account_mode = Some(su.account_mode.clone());
    prefs.is_pending_guest_email_link = false;
    prefs.pending_email = None;
    prefs.clear_pending_auth_flow(false);
    prefs.needs_password_setup = su.needs_password_setup;
    AuthOutcome::Success { user: su.user, message: su.message }
}

/// Verbatim port of completeAuthCode (PKCE token exchange branch), composed
/// on the same wiring seams (ONE implementation): the tree owns the
/// transport and the commits, the seams own the branch logic.
fn complete_auth_code(
    prefs: &mut AuthPrefsState,
    auth_code: &str,
    auth_api_url: &str,
    now_seconds: i64,
    transport: &mut Transport,
) -> AuthOutcome {
    // Single captures in the source's original read ORDER: verifier (its
    // first statement), then pendingAuthAction and isPendingGuestEmailLink,
    // then userId. No re-reads anywhere below.
    let verifier = prefs.google_pkce_verifier.clone();
    let pending_auth_action = prefs.pending_auth_action.clone();
    let is_pending_guest_email_link = prefs.is_pending_guest_email_link;
    let existing_user_id = prefs.user_id.clone();
    let (action, linking_guest) = match interpret_cb_code_pre(
        verifier.is_some(),
        pending_auth_action.as_deref(),
        is_pending_guest_email_link,
        existing_user_id.as_deref(),
    ) {
        CbCodePreDecision::Error(m) => return AuthOutcome::Error(m),
        CbCodePreDecision::ErrorClearFlow(m) => {
            prefs.clear_pending_auth_flow(false);
            return AuthOutcome::Error(m);
        }
        CbCodePreDecision::Ok { action, linking_guest } => (action, linking_guest),
    };
    let operation = operation_for_action(&action);
    let verifier = match verifier {
        Some(v) => v,
        None => return AuthOutcome::Error(for_response("expired code", AuthOperation::Callback)),
    };
    // Real JSON serialization (matching the wired Kotlin's JSONObject
    // puts): values are escaped, never raw-interpolated. Insertion order
    // matches the source's put order.
    let body = json_value_to_string(&JsonValue::Object(vec![
        ("auth_code".to_string(), JsonValue::Str(auth_code.to_string())),
        ("code_verifier".to_string(), JsonValue::Str(verifier)),
    ]));
    let token_url = format!("{auth_api_url}/token?grant_type=pkce");
    let response = match transport("POST", &token_url, Some(&body)) {
        Ok(r) => r,
        Err(_) => return AuthOutcome::Error(fallback(operation).to_string()),
    };
    match interpret_cb_code_response(
        &action,
        linking_guest,
        existing_user_id.as_deref(),
        prefs.pending_email.as_deref(),
        response.successful,
        &response.body,
    ) {
        CbCodeResponseDecision::ErrorClear(m) => {
            prefs.clear_pending_auth_flow(true);
            AuthOutcome::Error(m)
        }
        CbCodeResponseDecision::ErrorPlain(m) => AuthOutcome::Error(m),
        CbCodeResponseDecision::Ok { session, needs_password_setup, message, user, .. } => {
            // Source commit order: saveSession's writes, then the flags.
            apply_session_fields(prefs, &session, now_seconds);
            prefs.is_pending_guest_email_link = false;
            prefs.clear_pending_auth_flow(true);
            prefs.needs_password_setup = needs_password_setup;
            AuthOutcome::Success { user, message }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    struct Canned {
        queued: std::collections::VecDeque<HttpResponse>,
        requests: usize,
    }
    impl Canned {
        fn transport(&mut self) -> impl FnMut(&str, &str, Option<&str>) -> Result<HttpResponse, String> + '_ {
            move |_m, _u, _b| {
                self.requests += 1;
                self.queued
                    .pop_front()
                    .ok_or_else(|| "probe: no response queued".to_string())
            }
        }
    }

    fn resp(ok: bool, body: &str) -> HttpResponse {
        HttpResponse { successful: ok, body: body.to_string(), header_location: None }
    }

    struct Row {
        id: String,
        url: String,
        setup: fn(&mut AuthPrefsState),
        queued: Vec<HttpResponse>,
        expect: Vec<(String, String)>,
        now: i64,
    }

    fn load_rows() -> Vec<Row> {
        let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        p.push("fixtures/oracle/auth/results_authcb.tsv");
        let text = std::fs::read_to_string(p).unwrap();
        let mut rows = Vec::new();
        for line in text.lines() {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.get(1) != Some(&"link") {
                continue;
            }
            let id = cols[0].to_string();
            let kv = |prefix: &str| -> String {
                cols.iter()
                    .find_map(|c| c.strip_prefix(prefix))
                    .unwrap_or("")
                    .to_string()
            };
            let expect: Vec<(String, String)> = cols[2..]
                .iter()
                .filter_map(|c| c.split_once('='))
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect();
            let url = kv("url=");
            let setup: fn(&mut AuthPrefsState) = match id.as_str() {
                "c04" => |p: &mut AuthPrefsState| {
                    p.is_pending_guest_email_link = true;
                    p.user_id = Some("owner-x".into());
                },
                "c05" => |p: &mut AuthPrefsState| {
                    p.is_pending_guest_email_link = true;
                    p.user_id = Some("u1".into());
                    p.pending_email = Some("g@h.i".into());
                },
                "c14" => |p: &mut AuthPrefsState| {
                    p.google_pkce_verifier = Some("v123".into());
                    p.pending_auth_action = Some("GOOGLE".into());
                },
                "c16" => |p: &mut AuthPrefsState| {
                    p.google_pkce_verifier = Some("v123".into());
                    p.pending_auth_action = Some("GOOGLE".into());
                    p.user_id = Some("owner-y".into());
                },
                _ => |_| {},
            };
            let queued = match id.as_str() {
                "c01" | "c06" | "c10" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":"2026-01-01T00:00:00Z","is_anonymous":false}"#)],
                "c02" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","is_anonymous":false}"#)],
                "c03" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","confirmed_at":"2026-01-01","is_anonymous":false}"#)],
                "c04" => vec![resp(true, r#"{"id":"u-other","email":"a@b.c","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c05" => vec![resp(true, r#"{"id":"u1","email":"","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c09" => vec![resp(false, r#"{"code":401,"msg":"bad jwt"}"#)],
                "c11" => vec![],
                "c14" => vec![resp(true, r#"{"access_token":"T1","refresh_token":"R2","expires_in":3600,"user":{"id":"u9","email":"g@g.co","email_confirmed_at":"x","is_anonymous":false}}"#)],
                "c15" => vec![resp(true, r#"{"access_token":"T1"}"#)],
                "c16" => vec![resp(true, r#"{"access_token":"T1","user":{"id":"u9","email_confirmed_at":"x"}}"#)],
                "c17" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":null,"confirmed_at":null,"is_anonymous":false}"#)],
                "c18" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":null,"is_anonymous":false}"#)],
                "c19" => vec![resp(true, r#"{"id":"","email":"a@b.c","email_confirmed_at":"x","is_anonymous":false}"#)],
                "c20" => vec![resp(true, r#"{"id":"u1","email":"a@b.c","email_confirmed_at":12345,"is_anonymous":false}"#)],
                _ => vec![],
            };
            // expires fallback rows: pin now so now+3600 matches the recorded value
            let expires = kv("expires=");
            let now = match id.as_str() {
                "c01" | "c03" | "c05" | "c06" | "c14" => expires.parse::<i64>().unwrap() - 3600,
                _ => 0,
            };
            rows.push(Row { id, url, setup, queued, expect, now });
        }
        rows
    }

    /// Real-AuthPreferences store semantics locked by executing the actual
    /// class through its unitTest seam (fixtures/oracle/auth/results_prefs.tsv,
    /// rows s01-s03): userId changes bump the generation; clearSession bumps
    /// again, resets accountMode to "ANONYMOUS" and clears session fields;
    /// insecure stores drop credential writes.
    #[test]
    fn prefs_store_semantics_match_real_authprefs() {
        let mut p = AuthPrefsState::fresh();
        p.set_user_id(Some("u-1".into()));
        assert_eq!(p.session_generation, 1, "s01: userId change bumps generation");
        p.access_token = Some("AT".into());
        p.refresh_token = Some("RT".into());
        p.session_expires_at_seconds = 12345;
        p.account_mode = Some("EMAIL".into());
        p.pending_email = Some("p@q.r".into());
        p.pending_auth_action = Some("GOOGLE".into());
        p.google_pkce_verifier = Some("ver".into());
        p.is_pending_guest_email_link = true;
        p.needs_password_setup = true;
        p.clear_session();
        assert_eq!(p.session_generation, 2, "s01: clearSession bumps again");
        assert_eq!(p.account_mode.as_deref(), Some("ANONYMOUS"));
        assert!(p.user_id.is_none() && p.user_email.is_none());
        assert!(p.access_token.is_none() && p.refresh_token.is_none());
        assert_eq!(p.session_expires_at_seconds, 0);
        assert!(p.pending_email.is_none() && p.pending_auth_action.is_none());
        assert!(p.google_pkce_verifier.is_none());
        assert!(!p.is_pending_guest_email_link && !p.needs_password_setup);

        // s03: insecure store drops credential writes (writeSecureString removes).
        let mut insecure = AuthPrefsState::fresh();
        insecure.is_secure_storage_available = false;
        insecure.set_user_id(Some("u-3".into()));
        assert_eq!(insecure.user_id, None, "s03: insecure store drops userId");
    }

    #[test]
    fn link_tree_matches_kotlin_corpus() {
        let rows = load_rows();
        assert_eq!(rows.len(), 20);
        for row in rows {
            let mut prefs = AuthPrefsState::fresh();
            (row.setup)(&mut prefs);
            let mut canned = Canned { queued: row.queued.into_iter().collect(), requests: 0 };
            let mut transport = canned.transport();
            let outcome = complete_email_link(
                &mut prefs,
                &row.url,
                "https://supabase.example.co/auth/v1",
                row.now,
                &mut transport,
            );
            drop(transport);
            let get = |k: &str| -> String {
                row.expect
                    .iter()
                    .find(|(key, _)| key == k)
                    .map(|(_, v)| v.clone())
                    .unwrap_or_default()
            };
            let (cls, msg, uid) = match &outcome {
                AuthOutcome::Success { user, message } => {
                    ("success", message.clone(), user.id.clone())
                }
                AuthOutcome::VerificationRequired(m) => ("verify", m.clone(), String::new()),
                AuthOutcome::Error(e) => ("error", e.clone(), String::new()),
            };
            assert_eq!(cls, get("result"), "{} class", row.id);
            assert_eq!(msg, get("msg"), "{} msg", row.id);
            assert_eq!(uid, get("uid"), "{} uid", row.id);
            let opt = |v: &Option<String>| v.clone().unwrap_or_else(|| "null".to_string());
            assert_eq!(opt(&prefs.access_token), get("access"), "{} access", row.id);
            assert_eq!(opt(&prefs.refresh_token), get("refresh"), "{} refresh", row.id);
            assert_eq!(opt(&prefs.account_mode), get("mode"), "{} mode", row.id);
            assert_eq!(opt(&prefs.user_id), get("uidPref"), "{} uidPref", row.id);
            assert_eq!(opt(&prefs.user_email), get("emailPref"), "{} emailPref", row.id);
            assert_eq!(
                prefs.session_expires_at_seconds.to_string(),
                get("expires"),
                "{} expires",
                row.id
            );
            assert_eq!(prefs.needs_password_setup.to_string(), get("pwSetup"), "{} pwSetup", row.id);
            assert_eq!(
                prefs.is_pending_guest_email_link.to_string(),
                get("guestPending"),
                "{} guestPending",
                row.id
            );
            assert_eq!(opt(&prefs.pending_email), get("pendingEmail"), "{} pendingEmail", row.id);
            assert_eq!(opt(&prefs.pending_auth_action), get("action"), "{} action", row.id);
            assert_eq!(opt(&prefs.google_pkce_verifier), get("verifier"), "{} verifier", row.id);
            assert_eq!(canned.requests.to_string(), get("reqs"), "{} reqs", row.id);
        }
    }
    #[test]
    fn interpret_sign_in_wiring_seam_pins() {
        // NEW IMPLEMENTATION (2026-11-13) wiring-seam pins. Written for
        // execution on a build host; NOT executed in the wiring environment.
        let ok_body = r#"{"access_token":"AT","refresh_token":"RT","expires_in":3600,"user":{"id":"u1","email":"e@x.y","email_confirmed_at":"2026-01-01T00:00:00Z","is_anonymous":false}}"#;
        match interpret_sign_in_response(true, ok_body, "e@x.y") {
            SignInInterpretation::Success { user, message, session } => {
                assert_eq!(user.id, "u1");
                assert_eq!(user.email.as_deref(), Some("e@x.y"));
                assert_eq!(message, "Successfully signed in to CycleCare.");
                assert_eq!(session.mode, "EMAIL");
                assert_eq!(session.user_id.as_deref(), Some("u1"));
                assert_eq!(session.access_token.as_deref(), Some("AT"));
                assert_eq!(session.refresh_token.as_deref(), Some("RT"));
                assert_eq!(session.expires_at, None); // absent -> caller's clock point computes now+expires_in
                assert_eq!(session.expires_in, 3600);
            }
            other => panic!("expected Success, got {other:?}"),
        }
        // Error-body mapping (auth_errors corpus).
        assert_eq!(
            interpret_sign_in_response(false, r#"{"error":"invalid_grant","error_description":"Invalid login credentials"}"#, "e@x.y"),
            SignInInterpretation::Error("That email and password don't match. Check them and try again.".to_string())
        );
        // Parse failure AND non-object top level -> the source catch-all's
        // connect message (JSONObject(body) throws on both).
        assert_eq!(
            interpret_sign_in_response(true, "{not json", "e@x.y"),
            SignInInterpretation::Error("Unable to connect. Please check your network connection.".to_string())
        );
        assert_eq!(
            interpret_sign_in_response(true, "[1,2]", "e@x.y"),
            SignInInterpretation::Error("Unable to connect. Please check your network connection.".to_string())
        );
        // Unconfirmed -> VerificationRequired (caller commits clearSession).
        match interpret_sign_in_response(true, r#"{"access_token":"AT","user":{"id":"u1","email":"e@x.y"}}"#, "e@x.y") {
            SignInInterpretation::VerificationRequired(m) => {
                assert_eq!(m, "Please verify your email using the CycleCare link before signing in.")
            }
            other => panic!("expected VerificationRequired, got {other:?}"),
        }
        // saveSession's user fallback is the WHOLE body (not the empty
        // object readUser uses) - but on the sign-in path that fallback is
        // UNREACHABLE on Success: no "user" key means the confirmation
        // check sees the empty object and VerificationRequired fires first.
        // Pinned at both levels: the sign-in outcome is VERIFY...
        match interpret_sign_in_response(true, r#"{"id":"u9","email":"top@x.y","email_confirmed_at":"x","access_token":"AT"}"#, "fb@x.y") {
            SignInInterpretation::VerificationRequired(_) => {}
            other => panic!("expected VerificationRequired (confirmation sees the empty object), got {other:?}"),
        }
        // ...and extract_session_fields (shared with saveSession's other
        // callers) still falls back to the WHOLE body for its raw fields.
        let f = extract_session_fields(&JsonParser::new(r#"{"id":"u9","email":"top@x.y"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.user_id.as_deref(), Some("u9"));
        assert_eq!(f.user_email.as_deref(), Some("top@x.y"));
        // Raw expiry extraction: expires_at present (integer + numeric
        // STRING coercion), negative preserved, junk string -> None.
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":1767225600,"expires_in":10}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(1767225600));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"1767225600"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(1767225600));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":-5}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(-5));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"junk"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, None);
        assert_eq!(f.expires_in, 3600);
        // AOSP optLong's String branch is (long) Double.parseDouble - the
        // full Java grammar, NOT Long.parseLong: fractions truncate,
        // whitespace trims, exponents and hex floats parse, NaN casts to 0,
        // Infinity/overflow saturate, d/f suffixes parse.
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"3600.9"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(3600));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":" 3600 "}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(3600));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"1e3"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(1000));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"NaN"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(0));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"Infinity"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(i64::MAX));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"-Infinity"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(i64::MIN));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1p3"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(8));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"7d"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(7));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0xff"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, None); // hex float REQUIRES a p-exponent
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_in":"1e1"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_in, 10);
        // Hex conversion exactness (reviewer corrections): the exponent is
        // combined in i64 - "0x1.0p-2147483647" underflows to 0 (an i32
        // combination WRAPPED to Infinity/Long.MAX here); mantissas beyond
        // 53 bits round half-to-even on exact guard/sticky (truncating
        // accumulation would give 9007199254740991 below).
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1.0p-2147483647"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(0));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1p-2147483647"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(0));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1fffffffffffff.8p0"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(9007199254740992)); // round-half-even up, not truncation
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1fffffffffffff.8000000001p0"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(9007199254740992)); // sticky round up
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x.8p1"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(1));
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1.23456789abcdef0123456789p10"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(1165)); // >64-bit mantissa, sticky only
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1p1024"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(i64::MAX)); // Infinity saturates
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x0p999999999999999999999"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(0)); // zero mantissa, huge exponent
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1p999999999999999999999"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(i64::MAX)); // exponent beyond i64
        // Below-guard sticky (reviewer counterexample): the bit immediately
        // below the guard is set, all others clear - strictly ABOVE the tie,
        // must round UP. An off-by-one mask that excludes that bit sees a
        // clean tie and rounds to even (4503599627370496).
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x40000000000003p-2"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(4503599627370497));
        // Same shape with the below-guard bit CLEAR: an exact tie, rounds
        // to even (down). Pins the tie/above-tie distinction.
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x40000000000002p-2"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(4503599627370496));
        // Deep-subnormal band: the scale factor itself underflows, so the
        // returned float (0.0) differs from Java's (Double.MIN_VALUE) -
        // documented, drift/attack-only; the (long) cast maps both to 0.
        let f = extract_session_fields(&JsonParser::new(r#"{"expires_at":"0x1.8p-1075"}"#).parse().unwrap(), "EMAIL");
        assert_eq!(f.expires_at, Some(0));
        // apply_session_fields: None user_id KEEPS current (no generation
        // bump), None tokens SET null, expiry default uses caller's now.
        let mut prefs = AuthPrefsState { is_secure_storage_available: true, ..Default::default() };
        prefs.set_user_id(Some("keepme".to_string()));
        let gen_before = prefs.session_generation;
        let mut p2 = prefs.clone();
        apply_session_fields(&mut p2, &SessionFields {
            mode: "EMAIL".to_string(), user_id: None, user_email: None,
            access_token: None, refresh_token: None, expires_at: None, expires_in: 600,
        }, 1000);
        assert_eq!(p2.user_id.as_deref(), Some("keepme"));
        assert_eq!(p2.session_generation, gen_before);
        assert_eq!(p2.user_email, None);
        assert_eq!(p2.access_token, None);
        assert_eq!(p2.session_expires_at_seconds, 1600);
    }
    #[test]
    #[test]
    fn interpret_cb_seams_wiring_pins() {
        // kotlin_equals_ignore_case: Kotlin's ignoreCase folds per char
        // with Java's SIMPLE mappings (long s U+017F uppercases to S);
        // plain ASCII too.
        assert!(kotlin_equals_ignore_case("RECOVERY", "recovery"));
        // U+017F (long s) folds to S, so "recoverſ" is "RECOVERS" - NOT a
        // match (the reviewer caught a false positive expectation here).
        assert!(!kotlin_equals_ignore_case("recover\u{17f}", "recovery"));
        assert!(!kotlin_equals_ignore_case("recoverx", "recovery"));
        assert!(!kotlin_equals_ignore_case("recovery", "recover"));

        // --- params seam ---
        // A PRESENT but blank error_description does NOT fall through to
        // "error" (source: `value("error_description") ?: value("error")`
        // picks the present blank; isNullOrBlank then skips the branch).
        let p = vec![("error_description".to_string(), " ".to_string()),
                     ("error".to_string(), "access_denied".to_string())];
        assert!(matches!(interpret_cb_link_params(&p),
            CbLinkParamsDecision::Error(m) if m == for_response("expired link", AuthOperation::Callback)));
        // Real link error: '+' -> ' ' before the corpus mapping.
        let p = vec![("error_description".to_string(), "expired+link".to_string())];
        assert!(matches!(interpret_cb_link_params(&p),
            CbLinkParamsDecision::Error(m) if m == for_response("expired link", AuthOperation::Callback)));
        // code + token keys conflict.
        let p = vec![("code".to_string(), "abc".to_string()),
                     ("access_token".to_string(), "t".to_string())];
        assert!(matches!(interpret_cb_link_params(&p),
            CbLinkParamsDecision::Error(m) if m == MSG_CONFLICTING));
        // code alone delegates.
        let p = vec![("code".to_string(), "abc".to_string())];
        assert!(matches!(interpret_cb_link_params(&p), CbLinkParamsDecision::Code(c) if c == "abc"));
        // WHITESPACE-only code is blank (NOT isEmpty): falls to the token
        // branch, whose tokens are absent -> expired-link error.
        let p = vec![("code".to_string(), " ".to_string())];
        assert!(matches!(interpret_cb_link_params(&p),
            CbLinkParamsDecision::Error(m) if m == for_response("expired link", AuthOperation::Callback)));
        // Whitespace-only access token is blank -> expired-link error.
        let p = vec![("access_token".to_string(), " ".to_string()),
                     ("refresh_token".to_string(), "r".to_string())];
        assert!(matches!(interpret_cb_link_params(&p),
            CbLinkParamsDecision::Error(m) if m == for_response("expired link", AuthOperation::Callback)));
        // Tokens carry the raw expiry/type strings unconverted.
        let p = vec![("access_token".to_string(), "a".to_string()),
                     ("refresh_token".to_string(), "r".to_string()),
                     ("expires_at".to_string(), "1700000000x".to_string()),
                     ("type".to_string(), "recovery".to_string())];
        match interpret_cb_link_params(&p) {
            CbLinkParamsDecision::Tokens { access_token, refresh_token, expires_at, expires_in, link_type } => {
                assert_eq!(access_token, "a");
                assert_eq!(refresh_token, "r");
                assert_eq!(expires_at.as_deref(), Some("1700000000x")); // NOT parsed here
                assert_eq!(expires_in, None);
                assert_eq!(link_type.as_deref(), Some("recovery"));
            }
            other => panic!("expected tokens, got {other:?}"),
        }

        // --- link user seam ---
        // Status error maps through the corpus with CALLBACK.
        assert!(matches!(interpret_cb_link_user(false, "x", false, None, None, None, None),
            CbLinkUserDecision::Error(m) if m == for_response("x", AuthOperation::Callback)));
        // Parse failure -> the source catch-all message.
        assert!(matches!(interpret_cb_link_user(true, "{nope", false, None, None, None, None),
            CbLinkUserDecision::Error(m) if m == MSG_VERIFY_FAILED));
        // Non-object 2xx -> same catch-all (JSONObject ctor throws).
        assert!(matches!(interpret_cb_link_user(true, "[1]", false, None, None, None, None),
            CbLinkUserDecision::Error(m) if m == MSG_VERIFY_FAILED));
        // Blank id -> corpus fallback.
        assert!(matches!(interpret_cb_link_user(true, "{}", false, None, None, None, None),
            CbLinkUserDecision::Error(m) if m == fallback(AuthOperation::Callback)));
        // Unconfirmed NON-anonymous -> verify-first message.
        assert!(matches!(interpret_cb_link_user(true, r#"{"id":"u"}"#, false, None, None, None, None),
            CbLinkUserDecision::Error(m) if m == MSG_UNCONFIRMED));
        // ANONYMOUS skips the confirmation check entirely.
        match interpret_cb_link_user(true, r#"{"id":"u","is_anonymous":true}"#, false, None, None, None, None) {
            CbLinkUserDecision::Success(su) => {
                assert!(su.is_anonymous);
                assert_eq!(su.account_mode, "ANONYMOUS");
                assert!(!su.linked_guest && !su.password_recovery && !su.needs_password_setup);
                assert_eq!(su.message, "Your CycleCare email is verified and you are signed in.");
                assert!(su.user.is_anonymous);
            }
            other => panic!("expected success, got {other:?}"),
        }
        // Pending guest link + different account id -> mismatch error.
        assert!(matches!(interpret_cb_link_user(true, r#"{"id":"u2","email_confirmed_at":"x"}"#, true, Some("u1"), None, None, None),
            CbLinkUserDecision::Error(m) if m == MSG_DIFFERENT_ACCOUNT));
        // Same id passes and becomes a linked guest (needs password setup).
        match interpret_cb_link_user(true, r#"{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}"#, true, Some("u1"), None, None, None) {
            CbLinkUserDecision::Success(su) => {
                assert!(su.linked_guest && su.needs_password_setup);
                assert_eq!(su.account_mode, "EMAIL");
                assert_eq!(su.message, "Your guest data is now linked to your verified email. Set a password or use email sign-in.");
                assert_eq!(su.email, "e@x.y");
            }
            other => panic!("expected success, got {other:?}"),
        }
        // Recovery detection: link type case-folded, or pending action.
        match interpret_cb_link_user(true, r#"{"id":"u","email_confirmed_at":"x"}"#, false, None, None, None, Some("RECOVERY")) {
            CbLinkUserDecision::Success(su) => {
                assert!(su.password_recovery && su.needs_password_setup);
                assert_eq!(su.message, "Your reset link is verified. Set a new password to finish.");
            }
            other => panic!("expected success, got {other:?}"),
        }
        match interpret_cb_link_user(true, r#"{"id":"u","email_confirmed_at":"x"}"#, false, None, None, Some(AUTH_ACTION_PASSWORD_RESET), None) {
            CbLinkUserDecision::Success(su) => assert!(su.password_recovery),
            other => panic!("expected success, got {other:?}"),
        }
        // Email fallback: blank user email -> pendingEmail.orEmpty(); the
        // raw (possibly blank) value is carried for the commit's ifBlank.
        match interpret_cb_link_user(true, r#"{"id":"u","email_confirmed_at":"x"}"#, false, None, Some("p@x.y"), None, None) {
            CbLinkUserDecision::Success(su) => {
                assert_eq!(su.email, "p@x.y");
                assert_eq!(su.user.email.as_deref(), Some("p@x.y")); // readUser's fallback
            }
            other => panic!("expected success, got {other:?}"),
        }

        // --- code pre seam ---
        assert!(matches!(interpret_cb_code_pre(false, None, false, None),
            CbCodePreDecision::Error(m) if m == for_response("expired code", AuthOperation::Callback)));
        // Action default: guest-link pending -> GUEST_LINK, else GOOGLE.
        match interpret_cb_code_pre(true, None, true, None) {
            CbCodePreDecision::Ok { action, linking_guest } => {
                assert_eq!(action, AUTH_ACTION_GUEST_LINK);
                assert!(linking_guest);
            }
            other => panic!("expected ok, got {other:?}"),
        }
        match interpret_cb_code_pre(true, None, false, None) {
            CbCodePreDecision::Ok { action, linking_guest } => {
                assert_eq!(action, AUTH_ACTION_GOOGLE);
                assert!(!linking_guest);
            }
            other => panic!("expected ok, got {other:?}"),
        }
        // Already-signed-in gate: nonblank existing user and NOT linking.
        assert!(matches!(interpret_cb_code_pre(true, Some(AUTH_ACTION_MAGIC_LINK), false, Some("u1")),
            CbCodePreDecision::ErrorClearFlow(m) if m == MSG_ALREADY_SIGNED_IN));
        // ... but a link callback upgrades THIS account: gate skipped.
        match interpret_cb_code_pre(true, Some(AUTH_ACTION_GOOGLE_LINK), false, Some("u1")) {
            CbCodePreDecision::Ok { linking_guest, .. } => assert!(linking_guest),
            other => panic!("expected ok, got {other:?}"),
        }

        // --- code response seam ---
        // Status error: CLEAR commit + corpus message (operation from action).
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, false, "x"),
            CbCodeResponseDecision::ErrorClear(m) if m == for_response("x", AuthOperation::MagicLink)));
        // Parse failure: NO commit (the source's catch-all) - the plain/
        // clear distinction is the commit boundary, pinned.
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, true, "{nope"),
            CbCodeResponseDecision::ErrorPlain(m) if m == fallback(AuthOperation::MagicLink)));
        // Blank access token -> clear + fallback.
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, true, r#"{"user":{"id":"u"}}"#),
            CbCodeResponseDecision::ErrorClear(m) if m == fallback(AuthOperation::MagicLink)));
        // Blank user id -> clear + fallback.
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, true, r#"{"access_token":"t","user":{}}"#),
            CbCodeResponseDecision::ErrorClear(m) if m == fallback(AuthOperation::MagicLink)));
        // Link-callback account mismatch -> clear + mismatch message.
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_GUEST_LINK, true, Some("u1"), None, true,
                r#"{"access_token":"t","user":{"id":"u2","email_confirmed_at":"x"}}"#),
            CbCodeResponseDecision::ErrorClear(m) if m == MSG_DIFFERENT_ACCOUNT));
        // Unconfirmed -> clear + verify message.
        assert!(matches!(interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, true,
                r#"{"access_token":"t","user":{"id":"u"}}"#),
            CbCodeResponseDecision::ErrorClear(m) if m == MSG_VERIFY_EMAIL_FIRST));
        // Success on GUEST_LINK: saveSession extraction (EMAIL mode, AOSP
        // optLong grammar on string expires_at), needsPasswordSetup true.
        match interpret_cb_code_response(AUTH_ACTION_GUEST_LINK, true, Some("u1"), Some("p@x.y"), true,
                r#"{"access_token":"t","refresh_token":"r","expires_at":"3600.9","expires_in":1800,"user":{"id":"u1","email_confirmed_at":"x"}}"#) {
            CbCodeResponseDecision::Ok { session, email, needs_password_setup, message, user } => {
                assert_eq!(session.mode, "EMAIL");
                assert_eq!(session.access_token.as_deref(), Some("t"));
                assert_eq!(session.expires_at, Some(3600)); // "3600.9" via (long) Double.parseDouble
                assert_eq!(session.expires_in, 1800);
                assert_eq!(email, "p@x.y"); // blank user email -> pendingEmail
                assert!(needs_password_setup);
                assert_eq!(message, "Your guest data is now linked to your verified email. Set a password or use email sign-in.");
                assert_eq!(user.id, "u1");
            }
            other => panic!("expected ok, got {other:?}"),
        }
        // PASSWORD_RESET also needs setup; MAGIC_LINK does not.
        match interpret_cb_code_response(AUTH_ACTION_PASSWORD_RESET, false, None, None, true,
                r#"{"access_token":"t","user":{"id":"u","email_confirmed_at":"x"}}"#) {
            CbCodeResponseDecision::Ok { needs_password_setup, message, .. } => {
                assert!(needs_password_setup);
                assert_eq!(message, "Your reset link is verified. Set a new password to finish.");
            }
            other => panic!("expected ok, got {other:?}"),
        }
        match interpret_cb_code_response(AUTH_ACTION_MAGIC_LINK, false, None, None, true,
                r#"{"access_token":"t","user":{"id":"u","email_confirmed_at":"x"}}"#) {
            CbCodeResponseDecision::Ok { needs_password_setup, message, .. } => {
                assert!(!needs_password_setup);
                assert_eq!(message, "You're signed in to CycleCare.");
            }
            other => panic!("expected ok, got {other:?}"),
        }
    }

    fn interpret_flow_seams_wiring_pins() {
        // NEW IMPLEMENTATION (2026-11-13) wiring-seam pins for signUp,
        // continueAsGuest, setPassword and the status-only flows. Written
        // for execution on a build host; NOT executed in the wiring
        // environment.

        // ---- signUp ----
        // Error body mapping carries the clear-pending commit marker.
        assert_eq!(
            interpret_sign_up_response(false, r#"{"error":"user_exists","error_description":"User already registered"}"#, "e@x.y"),
            SignUpInterpretation::ErrorClearPending("An account with that email already exists. Sign in or reset your password.".to_string())
        );
        // Parse failure AND non-object top level -> the create catch-all,
        // with NO commit (ErrorPlain).
        assert_eq!(
            interpret_sign_up_response(true, "{not json", "e@x.y"),
            SignUpInterpretation::ErrorPlain("We couldn't create your account right now. Check your connection and try again.".to_string())
        );
        assert_eq!(
            interpret_sign_up_response(true, "[1,2]", "e@x.y"),
            SignUpInterpretation::ErrorPlain("We couldn't create your account right now. Check your connection and try again.".to_string())
        );
        // Unconfirmed -> VerificationRequired even with a token (never
        // retain an unverified session).
        match interpret_sign_up_response(true, r#"{"access_token":"T1","user":{"id":"u1","email":"e@x.y"}}"#, "e@x.y") {
            SignUpInterpretation::VerificationRequired(m) =>
                assert_eq!(m, "Check e@x.y for your CycleCare verification link. The app will open automatically after you verify; you can enter only then."),
            other => panic!("expected VerificationRequired, got {other:?}"),
        }
        // Confirmed but NO token -> VerificationRequired.
        match interpret_sign_up_response(true, r#"{"user":{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}}"#, "e@x.y") {
            SignUpInterpretation::VerificationRequired(_) => {}
            other => panic!("expected VerificationRequired, got {other:?}"),
        }
        // Whitespace-only token (unlocked edge; Kotlin isBlank) -> VERIFY.
        match interpret_sign_up_response(true, r#"{"access_token":" ","user":{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}}"#, "e@x.y") {
            SignUpInterpretation::VerificationRequired(_) => {}
            other => panic!("expected VerificationRequired, got {other:?}"),
        }
        // signUp's user fallback is the WHOLE body (UNLIKE signIn): a body
        // with no "user" key but top-level confirmation + token succeeds,
        // and the session fields read the top level.
        match interpret_sign_up_response(true, r#"{"id":"u9","email":"top@x.y","email_confirmed_at":"x","access_token":"T1","refresh_token":"R2","expires_in":600}"#, "e@x.y") {
            SignUpInterpretation::Success { user, message, session } => {
                assert_eq!(user.id, "u9");
                assert_eq!(message, "Your verified CycleCare account is ready.");
                assert_eq!(session.mode, "EMAIL");
                assert_eq!(session.user_id.as_deref(), Some("u9"));
                assert_eq!(session.access_token.as_deref(), Some("T1"));
                assert_eq!(session.expires_in, 600);
            }
            other => panic!("expected Success, got {other:?}"),
        }

        // ---- continueAsGuest ----
        assert_eq!(
            interpret_guest_response(false, r#"{"error":"anonymous sign-ins are disabled"}"#),
            GuestInterpretation::Error("Guest access is temporarily unavailable. Try again later or sign in with email.".to_string())
        );
        assert_eq!(
            interpret_guest_response(true, "{not json"),
            GuestInterpretation::Error("A secure guest session needs an internet connection. Please retry when online.".to_string())
        );
        assert_eq!(
            interpret_guest_response(true, "[1,2]"),
            GuestInterpretation::Error("A secure guest session needs an internet connection. Please retry when online.".to_string())
        );
        // Blank id or blank token -> the corpus fallback (no commit).
        assert_eq!(
            interpret_guest_response(true, r#"{"user":{"id":" "},"access_token":"G1"}"#),
            GuestInterpretation::Error("Guest access is temporarily unavailable. Please try again later.".to_string())
        );
        assert_eq!(
            interpret_guest_response(true, r#"{"user":{"id":"anon1"},"access_token":""}"#),
            GuestInterpretation::Error("Guest access is temporarily unavailable. Please try again later.".to_string())
        );
        // Success: whole-body user fallback + ANONYMOUS session extraction.
        match interpret_guest_response(true, r#"{"id":"anon1","access_token":"G1","refresh_token":"G2"}"#) {
            GuestInterpretation::Success { user_id, message, session } => {
                assert_eq!(user_id, "anon1");
                assert_eq!(message, "Your private guest session is ready.");
                assert_eq!(session.mode, "ANONYMOUS");
                assert_eq!(session.user_id.as_deref(), Some("anon1"));
                assert_eq!(session.refresh_token.as_deref(), Some("G2"));
            }
            other => panic!("expected Success, got {other:?}"),
        }

        // ---- setPassword ----
        assert_eq!(
            interpret_set_password_response(false, "{}", "e@x.y"),
            SetPasswordInterpretation::Error("Your password couldn't be updated right now. Please try again.".to_string())
        );
        // Malformed AND non-object bodies both yield the EMPTY user
        // (runCatching {} getOrElse empty) with the caller's email fallback.
        for body in ["{not json", "[1,2]"] {
            match interpret_set_password_response(true, body, "e@x.y") {
                SetPasswordInterpretation::Success { user, message } => {
                    assert_eq!(user.id, "");
                    assert_eq!(user.email.as_deref(), Some("e@x.y"));
                    assert_eq!(message, "Your password is set.");
                }
                other => panic!("expected Success, got {other:?}"),
            }
        }
        match interpret_set_password_response(true, r#"{"id":"u1","email":"n@x.y","email_confirmed_at":"x"}"#, "e@x.y") {
            SetPasswordInterpretation::Success { user, .. } => {
                assert_eq!(user.id, "u1");
                assert_eq!(user.email.as_deref(), Some("n@x.y"));
            }
            other => panic!("expected Success, got {other:?}"),
        }

        // ---- status-only flows ----
        assert_eq!(
            interpret_flow_status(true, "", crate::auth_errors::AuthOperation::MagicLink),
            FlowStatusInterpretation::Proceed
        );
        assert_eq!(
            interpret_flow_status(false, "{}", crate::auth_errors::AuthOperation::MagicLink),
            FlowStatusInterpretation::Error("We couldn't send a sign-in link right now. Please try again later.".to_string())
        );
        assert_eq!(
            interpret_flow_status(false, "{}", crate::auth_errors::AuthOperation::PasswordReset),
            FlowStatusInterpretation::Error("We couldn't send password reset instructions right now. Please try again later.".to_string())
        );
        assert_eq!(
            interpret_flow_status(false, r#"{"error":"user_exists"}"#, crate::auth_errors::AuthOperation::AccountLink),
            FlowStatusInterpretation::Error("That email is already linked to another account. Sign out, then sign in to that account.".to_string())
        );
    }
}
