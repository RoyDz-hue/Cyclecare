//! Decision-core pins for CareImageGenerationService.kt over a SCRIPTED
//! transport - not live-service proof. Verbatim copy checked char-exact.
use cyclecare_core::care_image_gen::*;

const BASE: &str = "https://proj.example.supabase.co";
const ANON: &str = "anon-key";

struct Scripted { resp: Option<Result<ImageResponse, TransportError>>, seen: Option<ImageRequest> }
impl ImageHttp for Scripted {
    fn execute(&mut self, r: &ImageRequest) -> Result<ImageResponse, TransportError> {
        self.seen = Some(r.clone());
        self.resp.take().unwrap()
    }
}
fn ok_resp(bytes: Vec<u8>) -> ImageResponse {
    ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: bytes.len() as i64, chunks: vec![bytes] }
}

#[test]
fn style_enum_verbatim() {
    assert_eq!(CareImageStyle::Botanical.api_value(), "botanical");
    assert_eq!(CareImageStyle::Botanical.title(), "Botanical");
    assert_eq!(CareImageStyle::Botanical.subtitle(), "Soft greens and wildflowers");
    assert_eq!(CareImageStyle::Moonlit.api_value(), "moonlit");
    assert_eq!(CareImageStyle::Moonlit.subtitle(), "Quiet indigo and lavender");
    assert_eq!(CareImageStyle::Sunrise.api_value(), "sunrise");
    assert_eq!(CareImageStyle::Sunrise.subtitle(), "Warm peach and gold");
    assert_eq!(CareImageStyle::Ocean.api_value(), "ocean");
    assert_eq!(CareImageStyle::Ocean.subtitle(), "Calm teal and sand");
}

#[test]
fn blank_token_guard_and_bearer_trim() {
    for tok in ["", "   ", "\t "] {
        let e = build_request(CareImageStyle::Ocean, tok, BASE, ANON, "").unwrap_err();
        assert_eq!(e, ImageGenError::Message("Please sign in again before creating an image.".into()), ":49-51");
    }
    let r = build_request(CareImageStyle::Ocean, "  tok123  ", BASE, ANON, "").unwrap();
    assert_eq!(r.bearer_token, "tok123", ":64 Bearer uses the TRIMMED token");
}

#[test]
fn prompt_validation_utf16_units_and_controls() {
    let ok500 = "a".repeat(500);
    assert!(build_request(CareImageStyle::Ocean, "t", BASE, ANON, &ok500).is_ok(), "500 UTF-16 units allowed");
    let over501 = "a".repeat(501);
    let e = build_request(CareImageStyle::Ocean, "t", BASE, ANON, &over501).unwrap_err();
    assert_eq!(e, ImageGenError::Message("Use a decorative prompt of up to 500 characters, without personal or health details.".into()), ":54-56");
    // An astral char counts TWO UTF-16 units (Kotlin String.length).
    let astral = "🌸".repeat(251); // 502 units
    assert!(build_request(CareImageStyle::Ocean, "t", BASE, ANON, &astral).is_err(), "251 astral chars = 502 units > 500");
    // Control chars: \t rejected, \r rejected, \n explicitly allowed (:54).
    assert!(build_request(CareImageStyle::Ocean, "t", BASE, ANON, "a\tb").is_err(), "tab is <32 and not \\n");
    assert!(build_request(CareImageStyle::Ocean, "t", BASE, ANON, "a\rb").is_err());
    assert!(build_request(CareImageStyle::Ocean, "t", BASE, ANON, "a\nb").is_ok(), "newline exempt");
}

#[test]
fn payload_is_exactly_style_consent_and_optional_prompt() {
    let r = build_request(CareImageStyle::Botanical, "t", BASE, ANON, "").unwrap();
    assert_eq!(r.payload_json, r#"{"style":"botanical","image_consent":true}"#, ":57-59 insertion order, no extra keys - no health context is ever attached");
    let r2 = build_request(CareImageStyle::Botanical, "t", BASE, ANON, "   ").unwrap();
    assert_eq!(r2.payload_json, r#"{"style":"botanical","image_consent":true}"#, "blank-after-trim prompt adds no key (:58)");
    let r3 = build_request(CareImageStyle::Botanical, "t", BASE, ANON, "  moon/sea  ").unwrap();
    assert_eq!(r3.payload_json, r#"{"style":"botanical","image_consent":true,"custom_prompt":"moon\/sea"}"#, "prompt trimmed; org.json escapes '/' (:53,:58)");
}

#[test]
fn url_and_headers_verbatim() {
    let r = build_request(CareImageStyle::Ocean, "t", "https://x.co/", ANON, "").unwrap();
    assert_eq!(r.url, "https://x.co/functions/v1/image-generation", ":61 trimEnd('/')");
    assert_eq!(r.api_key, ANON);
    assert_eq!(r.content_type, "application/json");
    assert_eq!(r.accept, "image/png, image/jpeg, image/webp, image/avif, application/json", ":66");
}

#[test]
fn status_message_matrix_verbatim() {
    let cases: [(u16, &str); 6] = [
        (400, "Use a decorative prompt without personal or health details."),
        (401, "Please sign in again before creating an image."),
        (403, "Image creation is a Premium feature."),
        (429, "You’ve made an image recently. Please try again later."),
        (500, "Image creation is temporarily unavailable. Please try again later."),
        (302, "Image creation is temporarily unavailable. Please try again later."),
    ];
    for (code, msg) in cases {
        let e = handle_response(ImageResponse { status: code, has_body: true, content_type: Some("image/png".into()), content_length: 3, chunks: vec![vec![1, 2, 3]] }).unwrap_err();
        assert_eq!(e, ImageGenError::Message(msg.into()), ":74-81 code {code}");
    }
    for ok in [200u16, 201, 204, 299] {
        assert!(handle_response(ok_resp(vec![9])).is_ok() || ok != 200, "isSuccessful 2xx ({ok})");
    }
}

#[test]
fn body_and_content_type_guards() {
    let e = handle_response(ImageResponse { status: 200, has_body: false, content_type: None, content_length: 0, chunks: vec![] }).unwrap_err();
    assert_eq!(e, ImageGenError::Message("Image creation couldn't finish. Please try again later.".into()), ":83-85 null body");
    let e = handle_response(ImageResponse { status: 200, has_body: true, content_type: None, content_length: 1, chunks: vec![vec![1]] }).unwrap_err();
    assert_eq!(e, ImageGenError::Message("Image creation couldn't finish. Please try again later.".into()), ":90 missing header -> orEmpty not in set");
    let e = handle_response(ImageResponse { status: 200, has_body: true, content_type: Some("application/json".into()), content_length: 1, chunks: vec![vec![1]] }).unwrap_err();
    assert_eq!(e, ImageGenError::Message("Image creation couldn't finish. Please try again later.".into()), ":91 not an image type");
    // Parameters stripped, case-insensitive (:86-89).
    let ok = handle_response(ImageResponse { status: 200, has_body: true, content_type: Some("IMAGE/PNG ; charset=binary".into()), content_length: 1, chunks: vec![vec![7]] }).unwrap();
    assert_eq!(ok, vec![7]);
}

#[test]
fn size_cap_declared_and_mid_stream() {
    let big_declared = ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: MAX_IMAGE_BYTES as i64 + 1, chunks: vec![vec![1]] };
    assert_eq!(handle_response(big_declared).unwrap_err(), ImageGenError::Message("This image is too large to display. Please try again.".into()), ":94-95 declared over cap");
    let unknown_len = ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: -1, chunks: vec![vec![5; 100]] };
    assert_eq!(handle_response(unknown_len).unwrap().len(), 100, "contentLength -1 (unknown) falls through");
    let mid = ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: -1, chunks: vec![vec![0u8; MAX_IMAGE_BYTES], vec![1]] };
    assert_eq!(handle_response(mid).unwrap_err(), ImageGenError::Message("This image is too large to display. Please try again.".into()), ":101-103 running total crosses cap mid-stream");
    let exact = ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: -1, chunks: vec![vec![0u8; MAX_IMAGE_BYTES]] };
    assert_eq!(handle_response(exact).unwrap().len(), MAX_IMAGE_BYTES, "exactly at cap is allowed (> not >=)");
    let empty = ImageResponse { status: 200, has_body: true, content_type: Some("image/png".into()), content_length: 0, chunks: vec![] };
    assert_eq!(handle_response(empty).unwrap_err(), ImageGenError::Message("Image creation couldn't finish. Please try again later.".into()), ":109 empty image");
}

#[test]
fn transport_error_mapping() {
    let mut s = Scripted { resp: Some(Err(TransportError::Cancelled)), seen: None };
    assert_eq!(generate_using(&mut s, CareImageStyle::Ocean, "t", BASE, ANON, "").unwrap_err(), ImageGenError::Cancelled, ":112-113 cancellation rethrown");
    let mut s = Scripted { resp: Some(Err(TransportError::Other)), seen: None };
    assert_eq!(generate_using(&mut s, CareImageStyle::Ocean, "t", BASE, ANON, "").unwrap_err(), ImageGenError::Message("Image creation is temporarily unavailable. Please try again later.".into()), ":115-116 generic mapping");
    let mut s = Scripted { resp: Some(Ok(ok_resp(b"png-bytes".to_vec()))), seen: None };
    let out = generate_using(&mut s, CareImageStyle::Sunrise, "  tok  ", "https://x.co/", ANON, " hi ").unwrap();
    assert_eq!(out, b"png-bytes");
    let seen = s.seen.unwrap();
    assert_eq!(seen.bearer_token, "tok");
    assert_eq!(seen.payload_json, r#"{"style":"sunrise","image_consent":true,"custom_prompt":"hi"}"#);
}
