# Period date consistency - research note (2026-10-23)

Status: IMPLEMENTED on main (2026-10-23) after the owner approved the flow
("Implement all approved"). Full Android build, Room migration execution and
UI pixels remain unverified in this environment - an APK/device check is
still required before calling this a live fix.

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

## What was implemented (2026-10-23)

- Onboarding no longer clamps the entered duration with coerceIn(2, 10).
  PeriodEpisodeEntry.decide (domain/health, pure + JVM-tested 6/6) separates
  the raw episode record from the typical-duration estimate: an unusual
  confirmed pair keeps its exact end date and does NOT drag the estimate.
- Popup: gentle "Your period lasted N days..." wording with "Dates are
  correct" / "Edit dates" (OnboardingScreen PeriodDateConfirmDialog); a
  choice is required, no dismiss-into-silence.
- Raw end date stored on OnboardingProfile.lastPeriodEndDateString (Room
  migration 8 -> 9, ALTER TABLE ADD COLUMN - compile-verified only, NOT run
  on a device). The user can still edit/delete via existing flows.
- Sync: profile payload carries last_period_end_date; the live v3 sync
  contract stores payloads as verbatim JSONB, so the raw date round-trips
  without any server clamp. Older installs ignore the new key; newer
  installs treat it as optional. The legacy sync_my_profile 2..10 clamp now
  only bounds the typical-duration ESTIMATE column, which is its contract.
- CycleRepository.validatePeriodDatePair delegates to PeriodDateConsistency
  (chronology/future hard errors only, warning for >7 days).
- PDF/JSON data exports include the raw end date.

## PatternEngine episode detection (corrected 2026-10-23, review round 2)

Precise current behavior: a new observed episode starts at a logged bleeding
day when (a) there is no current episode, or (b) it is more than the
typical-duration window after the current episode's start AND the previous
calendar day was NOT a logged bleeding day. Consecutive logged bleeding runs
are therefore never split - a fully logged 20-day period is one episode and
produces no fake ~10-day cycle intervals (regression-tested). Sparse logging
with gaps beyond the window keeps the previous window semantics: such gaps
are uncertain evidence (missing logs are unknown, not non-bleeding), and a
later bleeding log after a long gap may start a new episode. That is a known
limitation of sparse data, not a claim about when a period ended.

## Typical-duration estimate: precise current behavior

The estimate (periodDurationDays, 2..10) changes ONLY when: (1) the user
picks a duration option in onboarding/settings, or (2) the user enters a
typical-length (<=7 day) start/end pair in onboarding, which sets the
estimate to that pair's length. A confirmed unusual pair NEVER changes it.
There is NO adaptation from accumulated episode history - "several reliable
episodes" was discussed as design direction but is not implemented.

## Sync and consent notes

- Verbatim JSONB storage of the profile payload (including the raw end date)
  is verified by contract reading plus executable codec tests
  (HealthSyncProfilePayloadTest, 6/6): round-trip fidelity, old-payload
  compatibility (missing key tolerated), forward compatibility (unknown keys
  ignored, never stored or executed), strict typing/range checks on all known
  keys, malformed or wrong-type end dates rejected. Known keys keep their
  strict validation; ignoring unknown keys accepts no new data into records.
- Verbatim storage is NOT evidence sync is enabled: the health-sync consent
  gate stays closed until the user explicitly allows cloud sync; nothing here
  changes the consent flow.
- Old app installs reading a newer payload is only relevant after those
  installs receive one, which requires sync enabled on both devices.

## Deliberately NOT implemented (not approved / unresolved)

- Episode-end inference from gaps, and any use of missing logs as
  non-bleeding evidence.
- Automatic typical-duration adaptation from accumulated episodes.
- Seeding daily logs from an inferred onboarding range - never.
- Any probabilistic fertility/medical scoring.

## Confirmation-state invalidation

The unusual-entry confirmation applies to the exact pair entered: changing
the start date, the end date, or picking a preset duration resets it, and the
popup re-asks for the new pair. Each account has its own namespaced local
database, so an account change starts with a fresh profile and no inherited
confirmation.

## Design clarification: mixed-duration history (Roy's 20-day / 3-day scenario)

Scenario: a user confirms a real 20-day period; a later period lasts 3 days and
the app learns this from daily logs. Design requirements this raises (still
hypotheses pending the owner's policy answer):

1. **No automatic overwrite of history.** A later 3-day period never
   automatically overwrites, shortens or re-labels the earlier confirmed
   20-day record, and never erases the warning that was attached to it. Each
   episode is its own record with its own provenance. This protects history
   from silent algorithmic rewriting only: the user always keeps the right to
   correct or delete any of their own records, including confirmed ones, and
   privacy rights (edit, export, erase) take precedence over any "immutable"
   framing.
2. **Provenance is explicit per episode.** Sources: (a) start/end dates the
   user entered and confirmed; (b) daily flow logs the user recorded day by
   day; (c) anything inferred (always labeled an estimate). These describe
   origin, not an absolute superiority ranking: when sources conflict (e.g.
   confirmed dates disagree with the daily logs around them), the conflict
   needs visible reconciliation - surface the discrepancy and ask the user,
   never silently let one tier win. There is no hidden "realisation chip": the
   app only learns from data the user actually logged plus the existing
   pattern engine. No capability beyond that may be implied.
3. **The typical-duration estimate updates cautiously.** One new observation
   (the 3-day period) must not flip the typical-duration estimate on its own.
   Updates should weigh several recent reliable episodes. Whether and how a
   confirmed unusual episode feeds the "typical" statistics is a
   health-sensitive rule, not merely a preference: it needs explicit
   justification and safeguards before adoption. Proposed safeguard: exclude
   flagged-unusual episodes from the typical-duration window while keeping
   them fully in history, and document the clinical reasoning (a typical-range
   estimate should describe the user's normal pattern, not be dragged by an
   episode the user confirmed as unusual) - subject to validation, not assumed
   correct.
4. **Missing logs are not proof a period ended.** An inference layer must
   treat unlogged days as unknown, never as non-bleeding, and must not
   manufacture episode ends or starts from silence. When evidence is
   insufficient, the honest output is to ask the user, not to guess.
5. **Ended vs inferred stays distinguishable in the record.** An episode with
   user-confirmed start and end dates is fact. An episode whose end is
   inferred from a gap in logs is an estimate and must carry that label
   wherever it is displayed or used in predictions.

These remain design preparation only. No storage, schema, episode-detection or
wiring changes have been made; all stay on hold until the save policy and this
design are settled with the owner.
