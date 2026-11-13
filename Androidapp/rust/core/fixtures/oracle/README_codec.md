# Codec oracle provenance (results_codec_*.tsv)

Generated 2026-11-02 by compiling AOSP libcore org.json sources
(tag android-14.0.0_r1: JSONObject, JSONArray, JSONTokener, JSONStringer,
JSON, JSONException + minimal android.annotation/libcore.util annotation
stubs) with JDK 17 javac, then running the verbatim-extracted Kotlin codec
(/downloads/parity/codec/CodecParity.kt, from HealthSyncLocal.kt) against
the AOSP classes.

These results SUPERSEDE the earlier Maven org.json 20240303 outputs:
- AOSP JSONObject uses LinkedHashMap (insertion-ordered keys); Maven uses
  HashMap (scrambled order) -> different payload strings AND sha256 hashes.
- AOSP JSONStringer escapes "/" as "\/"; Maven does not.
- AOSP parses decimals as Double; Maven as BigDecimal.
The AOSP set is the production-faithful one (devices ship libcore org.json).

Locked device behaviors evidenced here:
- Insertion key order; -0.0 -> "-0"; integral doubles strip ".0"
- Float->Double widening (36.6f -> 36.599998474121094; 7.3f -> 7.300000190734863, cl09 - targeted rerun for the ops numeric trace, whole set re-executed and byte-identical)
- AOSP tokener: octal "010" -> 8, hex "0x", exponent literals -> Double,
  integer/long/double number typing
- integer() out-of-range -> "type_" (range_ unreachable in integer())
- Error vocabulary: missing_/type_/long_/range_/unknown_/record_identity/
  invalid_date/invalid_due_date/invalid_end_date/type_last_period_end_date
- hash(null) = sha256("null") = 74234e98...
- Profile parse ignores unknown keys (forward compat); settings parse rejects
  them (unknown_*); profile parse always resets is_completed/age/terms flags.

Counts: 9 log + 3 settings + 2 profile payload/hash cases, 18 log-parse,
10 settings-parse, 9 profile-parse cases, 1 hash-null case = 43 cases,
all matched byte-exact by cyclecare_core::sync_codec (tests/sync_codec_oracle.rs).

## Cross-version source evidence (added 2026-11-02, v13)

Source-diffed the codec-relevant org.json regions across the full supported
range (minSdk 24): android-7.0.0_r1, android-7.1.0_r1, android-14.0.0_r1
(executed), android-15.0.0_r1. Behaviorally IDENTICAL in: JSONObject key
map (LinkedHashMap), JSONStringer.string escape set (incl. \/),
JSONTokener.readLiteral (hex/octal/long/double typing, case-insensitive
literals), JSONTokener.nextString escapes, JSONObject.numberToString,
JSON.toDouble/toInteger/toLong/toBoolean/toString (only annotation/comment
diffs). So the host-executed android-14.0.0_r1 result evidences device
behavior for the whole supported range BY SOURCE IDENTITY, not by device
test.

## Scope limits (explicit)

- Host execution: AOSP libcore sources compiled with JDK 17 and run on the
  host JVM, not on a physical device or emulator.
- The oracle is android-14.0.0_r1 executed; 7.0/7.1/15 coverage is source
  diff only. Android 16 (API 36) source not yet diffed.
- Remaining Java-number exotic-bit risks: (a) lone-surrogate \uXXXX escapes
  in parsed strings (Java keeps lone UTF-16 surrogates; Rust replaces with
  U+FFFD) - unreachable from codec-generated payloads, possible only on
  hand-corrupted payloads; (b) Java vs ryu shortest-digit selection could in
  principle differ for exotic bit patterns - no divergence observed across
  all corpora; both are shortest round-trip.
- java_double_to_string (ryu-based JDK 17 Double.toString port) also fixed a
  trailing-zero expansion bug found via the verified-amount corpus
  (10000000.0 -> "1.0E7" digits "1", not "100000000").
