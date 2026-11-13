# Doctor-summary production ownership

`LearningEngine.generateDoctorSummary` now calls `doctor_summary_v1`, falling back to the unchanged `generateDoctorSummaryKotlin` body on unavailable/failed/unsupported/malformed JNI. Both the ViewModel and `generateClinicianReportText` call this method. No flags were activated.

Rust owns first valid log per parsed LocalDate, per-date symptom dedup/count, descending count and ascending UTF16 key order before top-three selection, pain >=7 with no clamp, exact heavy-flow case matches, verbatim prompt generation, and final ordered formatted-text distinct (no backfill after label collisions). The full Java date domain uses the existing strict `parse_local_date_iso_full` parser rather than chrono's lenient limited parser.

Kotlin is a platform text adapter only: its existing `String.trim`/blank predicate and exact existing `displayLabel` function supply symptom keys/labels. This preserves JVM/ART titlecase expansion and first UTF16 Char behavior without invented Rust casing tables. Any unpaired surrogate in transmitted strings causes whole-call Kotlin fallback before JSON; none are dropped or replaced. Notes, energy, settings, profile and identity are not payload fields. All work is local/pure/read-only.

The wire denies unknown/missing/wrong-type input fields, validates pain as i32, and responds with version 1 plus a string list. Kotlin checks exact envelope/field sets, genuine integer version, at most five nonempty unique strings and returns no partial results on malformed input. An empty string list is valid, distinct from fallback null.

`verify_host_xml.py` checks exactly 14 named classes and 119 individually pinned case names, attributes, bodies, classnames, and zero skips. It does not weaken the separate Stage-D 13-class/110-case gate. Run from Androidapp or pass an evidence XML directory. `DoctorSummaryNativeParityTest` requires actual JNI responses and cannot pass by skipping or silently using Kotlin only.

Qualification is Linux host only. Fresh Android native builds, Android execution, minified/signed release checks remain outstanding before distribution. The previous Android APK/native does not contain this implementation. No cloud run, phone install, release/key change or production-data action is included.
