//! Port of domain/ai/CareImageGenerationService.kt (main@31b88f5, 123
//! lines) - decision core only. OkHttp, Dispatchers.IO and the live
//! Supabase edge function are injected seams; tests use a scripted
//! transport and are NOT live-service proof. The no-health-context behavior
//! is preserved structurally: the request payload is built ONLY from the
//! style, the literal image_consent:true, and the validated caller-supplied
//! prompt - this module has no access to health data and adds no keys.
//!
//! Verbatim user-facing copy is kept character-exact, including the curly
//! apostrophe in the 429 message (:79).

use crate::kotlin_text::{kotlin_is_blank, kotlin_trim};
use crate::sync_codec::{json_value_to_string, JsonValue};

/// :13-19 illustration moods with apiValue/title/subtitle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CareImageStyle {
    Botanical,
    Moonlit,
    Sunrise,
    Ocean,
}

impl CareImageStyle {
    pub fn api_value(self) -> &'static str {
        match self {
            CareImageStyle::Botanical => "botanical",
            CareImageStyle::Moonlit => "moonlit",
            CareImageStyle::Sunrise => "sunrise",
            CareImageStyle::Ocean => "ocean",
        }
    }
    pub fn title(self) -> &'static str {
        match self {
            CareImageStyle::Botanical => "Botanical",
            CareImageStyle::Moonlit => "Moonlit",
            CareImageStyle::Sunrise => "Sunrise",
            CareImageStyle::Ocean => "Ocean",
        }
    }
    pub fn subtitle(self) -> &'static str {
        match self {
            CareImageStyle::Botanical => "Soft greens and wildflowers",
            CareImageStyle::Moonlit => "Quiet indigo and lavender",
            CareImageStyle::Sunrise => "Warm peach and gold",
            CareImageStyle::Ocean => "Calm teal and sand",
        }
    }
}

/// :26
pub const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
/// :62 - the accepted response content types.
const ACCEPTED_TYPES: [&str; 4] = ["image/png", "image/jpeg", "image/webp", "image/avif"];

/// :21 - the one exception type; carries only its verbatim user message.
/// Cancellation is NOT mapped to a message (:112-114 rethrows it), so it is
/// a separate variant.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImageGenError {
    Message(String),
    Cancelled,
    /// RAW source exception: Request.Builder's url()/header() validation
    /// runs BEFORE the try block (:60-69 vs :70), so OkHttp's
    /// IllegalArgumentException propagates out of generateUsing uncaught -
    /// it is NOT mapped to user-safe copy (Request.kt:172-174 @throws IAE;
    /// Headers.kt:440-459 checkName/checkValue). This variant represents
    /// that raw throw faithfully; it is not a proposed hardening and no
    /// caller should show it to users. Message text for header failures is
    /// verbatim OkHttp 4.10.0; the URL-failure message text is modeled
    /// ("unexpected url: <url>") - the exact 4.10.0 string sits past the
    /// fetch window of the verified source, so only the exception TYPE and
    /// propagation position are source-verified for URLs.
    RawIllegalArgument(String),
}

/// The fully-built request, exposing every byte the Kotlin builder sets so
/// tests pin them without any network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageRequest {
    pub url: String,
    pub api_key: String,
    pub bearer_token: String,
    pub content_type: String,
    pub accept: String,
    pub payload_json: String,
}

/// The injected HTTP seam's response view. `content_length` mirrors
/// ResponseBody.contentLength(): -1 when unknown. `chunks` models the
/// byteStream reads the Kotlin loop performs (:95-108).
#[derive(Debug, Clone)]
pub struct ImageResponse {
    pub status: u16,
    /// None models `response.body == null` when `has_body` is false, and a
    /// missing Content-Type header otherwise - see `has_body`.
    pub has_body: bool,
    pub content_type: Option<String>,
    pub content_length: i64,
    pub chunks: Vec<StreamChunk>,
}

/// One byteStream read (:97-100). `IoError` models input.read(buffer)
/// throwing mid-stream - caught by the blanket :115 handler and mapped to
/// the generic message. Chunk sizes are NOT forced to 16KB: the cumulative
/// cap decisions are chunk-size-independent (:101-103), and the source's
/// 16KB buffer is an internal granularity detail.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamChunk {
    Data(Vec<u8>),
    IoError,
}

/// Transport failure seam: :113 cancellation rethrown, :115 every other
/// exception mapped to the generic message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportError {
    Cancelled,
    Other,
}

pub trait ImageHttp {
    fn execute(&mut self, request: &ImageRequest) -> Result<ImageResponse, TransportError>;
}

/// :50-58 - guards + payload + request building. No I/O.
pub fn build_request(
    style: CareImageStyle,
    access_token: &str,
    base_url: &str,
    anon_key: &str,
    custom_prompt: &str,
) -> Result<ImageRequest, ImageGenError> {
    // :49-51
    if kotlin_is_blank(access_token) {
        return Err(ImageGenError::Message(
            "Please sign in again before creating an image.".into(),
        ));
    }
    // :53-56: trim first; length in UTF-16 units (Kotlin String.length);
    // control-char check per UTF-16 code unit (it.code), '\n' exempt.
    let prompt = kotlin_trim(custom_prompt);
    let utf16_len = prompt.encode_utf16().count();
    let has_bad_control = prompt
        .encode_utf16()
        .any(|u| u < 32 && u != b'\n' as u16);
    if utf16_len > 500 || has_bad_control {
        return Err(ImageGenError::Message(
            "Use a decorative prompt of up to 500 characters, without personal or health details.".into(),
        ));
    }
    // :57-59: org.json insertion order; custom_prompt only when the TRIMMED
    // prompt is non-blank.
    let mut pairs = vec![
        ("style".to_string(), JsonValue::Str(style.api_value().to_string())),
        ("image_consent".to_string(), JsonValue::Bool(true)),
    ];
    if !kotlin_is_blank(prompt) {
        pairs.push(("custom_prompt".to_string(), JsonValue::Str(prompt.to_string())));
    }
    let payload_json = json_value_to_string(&JsonValue::Object(pairs));
    // :60-69 - Request.Builder validation happens HERE, outside the
    // try/catch at :70, so failures are raw IAEs (RawIllegalArgument).
    let url = format!("{}/functions/v1/image-generation", kotlin_trim_end_slashes(base_url));
    okhttp_url_check(&url)?;
    okhttp_header_value_check("apikey", anon_key)?;
    let bearer = kotlin_trim(access_token).to_string();
    okhttp_header_value_check("Authorization", &format!("Bearer {bearer}"))?;
    // Content-Type/Accept names+values are literals in the source and
    // always pass checkName/checkValue.
    Ok(ImageRequest {
        url,
        api_key: anon_key.to_string(),
        bearer_token: bearer,
        content_type: "application/json".into(),
        accept: "image/png, image/jpeg, image/webp, image/avif, application/json".into(),
        payload_json,
    })
}

/// Request.kt:175-187 (okhttp 4.10.0): ws:/wss: are silently rewritten to
/// http:/https:, then toHttpUrl() throws IllegalArgumentException on an
/// invalid URL. FAITHFUL SUBSET: the full HttpUrl grammar (IDN, percent
/// encoding, ports, user-info, dot-segments) is NOT ported - the check is
/// scheme in {http, https} (case-insensitive) plus a non-empty host before
/// the first '/', '?' or '#'. Every baseUrl the app can produce
/// (SupabaseConfig.url) passes either way; this pins the exception SHAPE
/// and position, not the full URL grammar.
fn okhttp_url_check(url: &str) -> Result<(), ImageGenError> {
    let rewritten = if url.len() >= 3 && url[..3].eq_ignore_ascii_case("ws:") && !url[..4.min(url.len())].eq_ignore_ascii_case("wss:") {
        format!("http:{}", &url[3..])
    } else if url.len() >= 4 && url[..4].eq_ignore_ascii_case("wss:") {
        format!("https:{}", &url[4..])
    } else {
        url.to_string()
    };
    let lower = rewritten.to_lowercase();
    let rest = if let Some(r) = lower.strip_prefix("http://") {
        &rewritten[7..7 + r.len()]
    } else if let Some(r) = lower.strip_prefix("https://") {
        &rewritten[8..8 + r.len()]
    } else {
        return Err(ImageGenError::RawIllegalArgument(format!("unexpected url: {url}")));
    };
    let host: &str = rest.split(['/', '?', '#']).next().unwrap_or("");
    // Host = authority up to '@' user-info or ':' port; require SOMETHING
    // non-empty as the hostname.
    let authority = host.rsplit('@').next().unwrap_or("");
    let hostname = authority.split(':').next().unwrap_or("");
    if hostname.is_empty() {
        return Err(ImageGenError::RawIllegalArgument(format!("unexpected url: {url}")));
    }
    Ok(())
}

/// Headers.kt:450-459 checkValue (okhttp 4.10.0): each UTF-16 unit must be
/// '\t' or in '\u0020'..'\u007e', else IAE
/// "Unexpected char %#04x at %d in %s value" (+ ": <value>" unless the
/// header is sensitive). isSensitiveHeader = Authorization / Cookie /
/// Proxy-Authorization / Set-Cookie, case-insensitive (okhttp commit
/// 1fd7c0a, okhttp3/internal). %#04x is Java's zero-padded hex, min width
/// 4 including "0x".
fn okhttp_header_value_check(name: &str, value: &str) -> Result<(), ImageGenError> {
    for (i, u) in value.encode_utf16().enumerate() {
        let ok = u == 0x09 || (0x20..=0x7e).contains(&u);
        if !ok {
            let sensitive = name.eq_ignore_ascii_case("authorization")
                || name.eq_ignore_ascii_case("cookie")
                || name.eq_ignore_ascii_case("proxy-authorization")
                || name.eq_ignore_ascii_case("set-cookie");
            let mut msg = format!("Unexpected char 0x{u:02x} at {i} in {name} value");
            if !sensitive {
                msg.push_str(&format!(": {value}"));
            }
            return Err(ImageGenError::RawIllegalArgument(msg));
        }
    }
    Ok(())
}

/// String.trimEnd('/') - trailing slashes only, NOT whitespace.
fn kotlin_trim_end_slashes(s: &str) -> &str {
    s.trim_end_matches('/')
}

/// :71-110 - response validation and the capped accumulation loop.
pub fn handle_response(response: ImageResponse) -> Result<Vec<u8>, ImageGenError> {
    // :72-82
    if !(200..=299).contains(&response.status) {
        let message = match response.status {
            400 => "Use a decorative prompt without personal or health details.",
            401 => "Please sign in again before creating an image.",
            403 => "Image creation is a Premium feature.",
            429 => "You’ve made an image recently. Please try again later.",
            _ => "Image creation is temporarily unavailable. Please try again later.",
        };
        return Err(ImageGenError::Message(message.into()));
    }
    // :83-85
    if !response.has_body {
        return Err(ImageGenError::Message(
            "Image creation couldn't finish. Please try again later.".into(),
        ));
    }
    // :86-93: substringBefore(';'), trim, lowercase, membership check.
    let content_type = response
        .content_type
        .as_deref()
        .map(|h| h.split(';').next().unwrap_or(""))
        .map(|h| h.trim().to_lowercase())
        .unwrap_or_default();
    if !ACCEPTED_TYPES.contains(&content_type.as_str()) {
        return Err(ImageGenError::Message(
            "Image creation couldn't finish. Please try again later.".into(),
        ));
    }
    // :94-96: declared length pre-check. Kotlin contentLength() > MAX with
    // -1 (unknown) falls through.
    if response.content_length > MAX_IMAGE_BYTES as i64 {
        return Err(ImageGenError::Message(
            "This image is too large to display. Please try again.".into(),
        ));
    }
    // :97-108: 16KB reads; the running total is checked AFTER each read.
    let mut output: Vec<u8> = Vec::new();
    let mut total: usize = 0;
    for chunk in &response.chunks {
        let data = match chunk {
            StreamChunk::Data(d) => d,
            // :97 read fault -> caught at :115, generic message.
            StreamChunk::IoError => {
                return Err(ImageGenError::Message(
                    "Image creation is temporarily unavailable. Please try again later.".into(),
                ))
            }
        };
        total += data.len();
        if total > MAX_IMAGE_BYTES {
            return Err(ImageGenError::Message(
                "This image is too large to display. Please try again.".into(),
            ));
        }
        output.extend_from_slice(data);
    }
    // :109-110
    if output.is_empty() {
        return Err(ImageGenError::Message(
            "Image creation couldn't finish. Please try again later.".into(),
        ));
    }
    Ok(output)
}

/// :45-118 - generateUsing composed over the injected transport. The
/// catch(:111-116) mapping lives here: cancellation propagates, the
/// module's own errors pass through, every other transport fault becomes
/// the generic message.
pub fn generate_using(
    http: &mut dyn ImageHttp,
    style: CareImageStyle,
    access_token: &str,
    base_url: &str,
    anon_key: &str,
    custom_prompt: &str,
) -> Result<Vec<u8>, ImageGenError> {
    let request = build_request(style, access_token, base_url, anon_key, custom_prompt)?;
    match http.execute(&request) {
        Err(TransportError::Cancelled) => Err(ImageGenError::Cancelled),
        Err(TransportError::Other) => Err(ImageGenError::Message(
            "Image creation is temporarily unavailable. Please try again later.".into(),
        )),
        Ok(response) => handle_response(response),
    }
}
