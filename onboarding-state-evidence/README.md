# Live onboarding non-date screen-state reducer

Twenty actual OnboardingViewModel event handlers now invoke a Rust-owned reducer
through the existing atomic StateFlow.update loop. Rust owns the next projected
screen state: next/previous step bounds, ordered goal/symptom toggles, single/multi
answer selection and ordered answer-map update, dialogs/privacy toggles, age/terms/
cloud choices, cycle option, reminder, energy/sleep/deep-baseline and date-confirm/
edit presentation. This does not grant remote consent or persist onboarding.
The original Compose renderer/layout/copy and date/clock/network/persistence code
are not changed. NEXT still invokes the original question-loading effect AFTER
state update, including at the final step. No old onboarding_vm.rs activation.

Complete original twenty update-lambda expression bodies are extracted with only trailing whitespace stripped
into explicit Kotlin fallback branches, with argument/lambda bindings added
outside the bodies. Original-expression table/extraction script are preserved in
execution evidence. JNI receives only21 projected state fields plus typed event;
copy restores untouched original date fields, dynamic question objects/loading,
raw duration options and other fields. Ordered sets/maps are explicit vectors;
duplicates/unknown actions/wrong schemas/types fail closed to whole fallback.
Unsupported surrogate content also selects whole fallback. Strict output schema
and unchanged total-step binding, not a full expected-policy recomputation.

Three direct-native host cases require nonnull before original-expression oracle
and shared reducer equality. Cover all20 actions, full Int step/cycle edges,
ordered toggles/maps, null reminder, exact text, generated event sequences,
untouched date/question identity, malformed/duplicate/unavailable/surrogate input.
Actual VM event sequences independently compare to original expressions and retain
the progressive question-load effect. Existing VM7/persistence5 are unchanged.
Strict45class316identity suite extends prior301. This is real screen-state
ownership but date decisions, derived UI, async question work, Android StateFlow
storage, full layout/navigation and broader app state/storage/effects/release are
not wholly Rust-owned. No pixel/layout claim from state tests or unchanged Compose.

Same-process payload can carry sensitive goals/answers/symptoms and choice state.
No new remote effect, real records, production flag, cloud request/retry/polling,
release key, money, personal installation or distribution. Android static witness
packaging is to be batched with later compatible UI work, not claimed here.
Serde derive is now a direct core dependency using already locked packages; both
lockfiles and current E7 lock-hash/codemagic pins cascade. Initial Rust compile
lacked that direct dependency and was fixed. A first debug Rust test link ran out
of disk; only regenerable test targets/incremental caches were cleared, and the
complete rerun uses jobs1/incremental0/dev+test debug0. No failed run is a pass.
