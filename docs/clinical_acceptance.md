# CycleCare clinical acceptance matrix

**Status: NOT clinically approved, not clinically validated, and not signed off.** These engineering tests exercise current local behavior only; passing tests do not establish clinical safety. Only a qualified clinical reviewer may approve release.

## Scope and safety boundary

- `SafetyEngine` is local-only triage language, not diagnosis, treatment, emergency dispatch, contraception, pregnancy dating, or a substitute for care.
- The app must preserve uncertainty in calendar estimates: estimates cannot confirm ovulation and must not be used as contraception.
- Pregnancy mode may provide general educational information only. It must not diagnose pregnancy complications, promise outcomes, replace prenatal care, or give medication/treatment instructions without clinician-approved content.
- If AI is denied, unavailable, times out, or the network fails, the local safety path must still run; the UI must not imply an AI answer was obtained. A failure must not suppress an urgent local warning.
- Non-English or very short/ambiguous input must not be treated as a negative safety screen. The product needs a safe language/accessibility route (plain words, interpreter/clinician escalation, or equivalent) before clinical approval.

## Regression test matrix

| Area | Input/condition | Expected safety behavior | Evidence / test status |
|---|---|---|---|
| Severity + red flag | Fainting plus very heavy bleeding | `URGENT`, explicit urgent-care prompt; no diagnosis | `ClinicalSafetyAcceptanceTest` |
| Severity + red flag | Heavy bleeding plus severe pain | `URGENT`, prompt urgent care and uncertainty | `ClinicalSafetyAcceptanceTest` |
| Red flag | Fainting alone | `URGENT`; avoid driving if unwell | Existing behavior + acceptance test |
| Severity | Severe pain alone | `SEEK_PROFESSIONAL_ADVICE`; clinical review wording | Acceptance test |
| Vaginal symptoms | New sores/lesions | Professional assessment; no diagnosis | Acceptance test |
| Urinary symptoms | Burning plus urgency/frequency | Prompt clinical advice; escalation if fever/flank pain/worse | Acceptance test |
| Negation | “No fainting … mild cramps” | Must not create a fainting/urgent flag from negated words | Existing regression + acceptance test |
| Combined observations | Negated symptom followed by a positive urgent observation | Positive observation remains assessable | Existing regression |
| Calendar uncertainty | Calendar estimate copy | Says calendar-based estimates, cannot confirm ovulation, must not be contraception; no “fertile window/ovulation peak/best conception days” promise | `PregnancyCalendarCopyTest` |
| Pregnancy boundaries | Pregnancy mode copy/content | General information only; confirm dates and care with a qualified clinician; no diagnosis or treatment claim | Copy review required |
| Limited English | “I fainted and soaking” / short plain-English wording | Recognise supported plain terms; do not infer a diagnosis | Acceptance test; vocabulary coverage needs clinical review |
| Denied AI/network | AI denied, unavailable, timeout, or offline | Local path remains available; no fabricated AI answer; urgent local result is not suppressed | Integration/manual review required (no network call in unit test) |
| Non-English / unmatched input | Unmatched Latin-script, non-Latin, mixed-language or ambiguous text | No reassuring negative; explicitly limited assessment, clinician/interpreter route; preserve recognised or saved urgency | `SafetyEngineInputTest`; these are fallback checks, not multilingual clinical validation |

## Clinical source mapping (public, retrieved 2026-10-19)

- [NICE NG88 recommendations](https://www.nice.org.uk/guidance/ng88/chapter/recommendations): take history of bleeding, related symptoms and quality-of-life impact; account for natural cycle variability; investigate severe dysmenorrhoea/related symptoms clinically; refer when symptoms are severe or treatment fails. NICE does not authorize this app to diagnose or triage emergencies.
- [NHS Heavy periods](https://www.nhs.uk/conditions/heavy-periods/): heavy bleeding indicators include changing products every 1–2 hours, bleeding over 7 days, clots, activity impact, tiredness or shortness of breath; seek GP advice for severe pain, persistent heavy periods, intermenstrual/post-coital bleeding, or associated urinary/bowel/sex pain.
- [NHS Vaginal discharge](https://www.nhs.uk/symptoms/vaginal-discharge/): changed smell/colour/texture, increased discharge, itch/soreness, bleeding, dysuria, pelvic pain warrant help; do not self-diagnose; avoid perfumed irritants and douching.
- [ACOG Heavy Menstrual Bleeding](https://www.acog.org/womens-health/faqs/heavy-menstrual-bleeding): heavy bleeding includes >7 days or soaking one or more pads/tampons hourly for several hours; causes include pregnancy-related conditions and other problems; clinical evaluation may include pregnancy testing and labs.
- [ACOG Abnormal Uterine Bleeding](https://www.acog.org/womens-health/faqs/abnormal-uterine-bleeding): hourly product changes for >2 hours with chest pain, shortness of breath, lightheadedness/dizziness require emergency care; abnormal bleeding needs clinician evaluation and may require pregnancy testing. ACOG states its public FAQ is educational and not a substitute for physician advice.

## Signoff checklist — intentionally empty

- [ ] Named qualified clinician reviewer (licence/registration and jurisdiction recorded)
- [ ] Clinician reviewed every red-flag threshold and escalation phrase against intended population and local emergency pathways
- [ ] Clinician reviewed pregnancy, contraception, fertility-calendar, and diagnosis boundaries
- [ ] Clinician reviewed supported languages, plain-language vocabulary, interpreter/accessibility route, and non-English failure behavior
- [ ] Clinician reviewed AI denial/offline behavior and confirmed no fabricated answer or suppressed local warning
- [ ] Privacy, safeguarding, and emergency-service wording reviewed for target deployment jurisdictions
- [ ] Release clinical signoff recorded outside this engineering document

**Required reviewer qualification:** independent licensed clinician with relevant reproductive/sexual-health and triage expertise, authorized to practice in the target deployment jurisdiction; add a second reviewer or specialist where pregnancy/urgent-care scope requires it. Engineering test authors cannot self-approve.

## Language limitation and engineering fallback

`SafetyEngine.evaluateInput` still uses an English keyword allow-list and cannot establish that it understands an entire message. Unmatched nonblank text now yields an explicit **Safety assessment limited** result rather than a routine/negative screen, including Latin-script languages that script detection would miss. The message says that no matched warning does not mean symptoms are safe and offers a qualified clinician and interpreter route. Existing more urgent findings win; this fallback does not invent a diagnosis, classify unknown text as an emergency, write health records, or change clinical thresholds.

Engineering regressions cover representative unmatched text, mixed input, negation and saved findings. They do not establish language coverage or approve the wording. A clinician-reviewed supported-language/accessibility strategy, vocabulary/negation review, user testing and the signoff checklist above remain required. Do not claim multilingual clinical coverage until actually reviewed.
