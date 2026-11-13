# Live onboarding date-entry screen-state ownership

Actual four ViewModel handlers (exact start, approximate chip, exact end, preset/
ongoing duration) now invoke strict Rust projected-state reducer inside original
atomic StateFlow.update. Rust owns exact/raw end field selection, chronology clamp,
period-decision integration, typical/unusual warning/confirmation selection, date
picker/reset state and duration option text. Existing period full decision module
is reused directly in Rust. Original future behavior is unchanged: these setters
still store selected future dates but the period decision retains the current
estimate and has no unusual confirmation. This is migration, not a behavior fix.
Int days-1 wraps before widening/coerceAtLeast; inclusive Long span option text is
NOT the narrowed Int estimate. Full Java LocalDate epoch bounds and raw input
object binding are enforced, not chrono dates or arbitrary returned native dates.

JVM retains exact platform primitives, original lazy effective-start fallback and
clock sequencing. Evidence runs inside each atomic retry. It evaluates today only
when original period decision runs; approximate/no-existing-end paths read none;
exact-end may read effective-start clock before a distinct decision clock. Ongoing
duration still evaluates/discards its original now call. Preset plusDays/overflow
is evaluated before JNI, so original exception type escapes. On native unavailable/
invalid/unsafe text, complete original expressions replay captured clock values
without another read, preserving midnight boundary and exception behavior. No
clock memoization across retries. Original source oracles preserve four complete
update bodies except explicit event/lambda binding, injected clock/effective-start
primitive and explicit original episode oracle names. Original repository date
validation shared dependency stays as before, independently qualified earlier.

Strict9field input/10field state and exact version/type output; result dates must
bind to actual current/event/evidence LocalDate objects. Decoder is structural/
date binding only, not full expected-policy recomputation. Local payload contains
sensitive dates/chips/duration/presentation text but no new remote or storage effect.
Original Compose, derived getters, confirm/edit handlers and confirmed-end boundary
remain unchanged, as do network/question loading/persistence/effects.

Direct JNI3 require nonnull before original-expression state equality, full date
extremes and wrapped spans, chronology/future/raw end/unusual estimate/option text,
all original chip presets/Int duration boundaries, changing clock sequence counts,
LocalDate overflow exception parity, generated states, schemas/date binding and
unavailable/surrogate fallback with no extra clock reads. Actual VM date-event and
confirmation/confirmed-end sequences are behavior evidence, not per-callback
nonnull JNI. Strict47class322identity gate extends accepted319. No pixel/layout/
navigation/full-onboarding claim. Android static witnesses deferred for compatible
batch; no cloud/retry/polling, real records, release/flags, spending/distribution.
