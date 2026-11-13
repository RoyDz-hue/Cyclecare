# Progressive onboarding question output and live question-state publication

Continues the accepted live20action reducer with the actual steps4..6 question
flow. LocalAdaptiveOnboardingBank.questionsFor now asks Rust for complete output:
selection, order, ids, prompts, options and multiselect. JVM retains its original
join/lowercase primitives (prior-answer flatten/lowercase only for step5) and
complete unchanged explicit Kotlin oracle. Exact original question string/option
literals were extracted from source, not a re-authored bank.

The three real ViewModel update stages now have native-first state reduction:
LOCAL inserts/replaces the step's local questions preserving current loading;
START sets loading without changing entries; GENERATED accepts nonempty response
or keeps the current question map, and clears loading. Full ordered step map is
encoded/decoded strictly; duplicate steps rejected, duplicate question ids/options
and arbitrary empty strings retained as the original typed-state semantics allow.
Complete original update expressions remain explicit fallback. Each is inside
original atomic StateFlow.update retry loop. Snapshot capture, step guard, exact
local-first then optional-service ordering, coroutine transport and timing/race
semantics are unchanged. No stale-response improvement or network-policy change
is silently added. Existing old question/state modules remain inactive.

Direct JNI3 require nonnull before full independent original bank/state equality:
all64 goal combinations, step/Int bounds, prior rest/energy, exact Unicode/JVM
lowercase and output copy; ordered map replacement/load/null/empty responses;
actual VM progression4..6 and final-step reload; schema/types/missing/duplicates/
unknown actions/unavailable/surrogate fallback. VM behavior is not proof of
nonnull native output per callback; direct pure JNI and VM sequences are separate.
Strict46class319identity gate extends accepted316. Original Compose bytes not
changed, no pixels/layout/navigation or complete onboarding claim from these tests.
Date/derived UI, async network/parsing, Android state storage, persistence/effects,
full design verification and release qualification remain broader work.

Payload may contain sensitive goal/answer text, question content and existing
question map, same-process only. No new remote/storage effect or real test data.
No cloud/retry/polling, production flags, release keys, spending or distribution.
Android witnesses are batched with accepted20action live state for later static
qualification, not a device runtime claim or authority.
