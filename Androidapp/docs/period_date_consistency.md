# Period date consistency - research note (2026-10-23)

Status: research + prepared validator only. NOT wired into any UI. NOT a live
fix. Save policy and storage design await the owner's decision.

## Defect found (verified in code)

Onboarding silently rewrites the entered period start/end duration with
`coerceIn(2, 10)` (OnboardingViewModel.setExactDate / setExactEndDate). A true
20-day entry is stored as 10 and the raw end date is persisted nowhere
(OnboardingProfile keeps only the start date + duration estimate). The backend
independently clamps its synced copy (`GREATEST(2, LEAST(..., 10))` in
20261018110000_cyclecare_backend_contract_v2.sql), so server-side clamping is
NOT preservation: it silently changes health data and can diverge across
devices.

## Downstream clamps inventory

- OnboardingViewModel: `coerceIn(2, 10)` on entered duration (the defect).
- Backend v2 contract: 2..10 clamp on the synced duration estimate.
- PatternEngine episode detection: `episodeWindow = periodDuration.coerceIn(1, 10)`;
  a real long logged episode can be split, creating artificial cycle starts and
  contaminating cycle-length statistics.
- PatternEngine.calculatePhaseForDate: `periodDuration.coerceIn(2, 10)` for
  phase bounds.
- CycleSettings.periodDuration documents "2 - 10".

## Separation proposal (DESIGN HYPOTHESES - not approved, not clinical algorithms)

1. Typical period duration (settings.periodDuration, 2..10 contract): a setup
   estimate for prediction windows/display. Never derived silently from an
   unusual entered episode.
2. Raw episode record: what the user actually entered/logged, preserved as
   entered. Open: where the onboarding end date should live.
3. Episode detection from logged bleeding runs: hypothesis only. Missing logs
   are NOT non-bleeding observations and must not create artificial episode
   starts. No seeded daily logs from an inferred onboarding range.

No schema, storage or episode-detection changes until the design and authority
are resolved.

## Prepared validator (committed, tested, NOT wired)

`domain/health/PeriodDateConsistency.kt`: only chronology (end before start)
and future dates are hard errors; every real duration stays confirmable;
unusual durations (>7 days, ACOG patient guidance:
https://www.acog.org/womens-health/faqs/abnormal-uterine-bleeding) carry a
gentle confirmation message, firmer when the period is ongoing. The validator
never claims the entry is saved. 9/9 tests pass on an isolated JVM compile
(PeriodDateConsistencyTest); not run against the full Android build.

## Open decisions (owner)

- Save policy for a confirmed unusual duration (Roy's answer pending).
- Storage design for the raw episode record (Room migration risk flagged).
- Popup UX wording/placement (approved shape: gentle "Your period lasted N
  days...", Edit dates / Dates are correct).
