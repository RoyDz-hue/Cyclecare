# CycleCare Comprehensive Evolution Implementation Plan
## Transforming Period Tracker into a Personal Women's Health Intelligence System

This implementation plan incorporates the full progressive product philosophy and the Master Build Specification into the CycleCare Android codebase while keeping health tracking neutral, accurate, and intelligently layered.

---

## 1. Architecture & Persistence Updates

### A. Extended Onboarding & Baseline Entity (`OnboardingProfile.kt` & `CycleSettings.kt`)
- Extend `OnboardingProfile`:
  - `typicalSymptomsCsv: String` (e.g., `"cramps,headaches,fatigue"`)
  - `typicalEnergy: String` (`"LOW"`, `"NORMAL"`, `"HIGH"`, `"VARIABLE"`)
  - `typicalSleepQuality: String` (`"DEEP"`, `"RESTLESS"`, `"LIGHT"`, `"VARIABLE"`)
  - `hasCompletedDeepBaseline: Boolean`
- Update `CycleCareDatabase.kt`:
  - Increment database version to 4 with clean migration / fallback.
- Update `CycleRepository.kt`:
  - Store and retrieve the enhanced onboarding profile and baseline preferences.

---

## 2. Progressive Onboarding: Optional "Learn About Me" Flow
*(Files: `OnboardingViewModel.kt`, `OnboardingScreen.kt`)*

- Add **Step 6: "Learn About Me"** (Optional progressive step after the initial cycle picture is calculated in Step 5):
  - Headline: *"Want CycleCare to understand you a little better?"*
  - Subtitle: *"This helps us learn what is typical for you versus what is new."*
  - Three gentle, high-impact sections:
    1. *"What do you usually notice around your period?"* (cramps, headaches, tiredness, mood changes, bloating, breast tenderness, back pain, acne, appetite changes, nothing noticeable, I'm not sure)
    2. *"How about your energy?"* (Steady, Lower before period, Peaks mid-cycle, It changes)
    3. *"And your sleep?"* (Generally good, Restless before period, Hard to fall asleep, Variable)
  - Clear dual CTA:
    - Primary: **"Save & Continue to CycleCare"**
    - Secondary: **"Skip for now — learn as I go"**

---

## 3. Continuous Intelligence: "CycleCare Learns" & "You vs You" Baseline
*(Files: `LearningEngine.kt`, `HomeScreen.kt`, `InsightsScreen.kt`, `PatternEngine.kt`)*

### A. Upgraded `LearningEngine.kt`
- Compute multi-cycle statistics:
  - Exact average vs median cycle length and standard deviation.
  - Cycle shift detection: compare the last 2 cycles against the overall historical baseline.
  - Symptom recurrence detection: correlation between cycle phase (menstrual, follicular, fertile, luteal) and symptoms/energy.
- Generate structured `LearnedObservation`:
  - Category: *"Your pattern"* (e.g., premenstrual fatigue, ovulatory vitality).
  - Category: *"Your usual cycle"* (e.g., baseline duration, consistency).
  - Category: *"Something changing"* (e.g., recent cycles running longer or shorter).
  - Category: *"Still learning"* (e.g., sleep data needed).

### B. "CycleCare Learns" Home Module (`HomeScreen.kt`)
- Dedicated carousel/cards on the Home screen showing what the app has learned about her so far.
- Adds an explicit *"Why this appeared"* toggle with clear clinical or observational explanations.

### C. "My Baseline" (You vs You) (`InsightsScreen.kt`)
- Replace generic charts with **"You vs You"**:
  - Your Usual Cycle vs Current Cycle.
  - Your Usual Period Bleeding Duration.
  - Typical Phase-by-Phase Energy & Sleep Trends.
  - Common Recurring Symptoms vs New Deviations.
- Longitudinal **Personal Health Timeline**:
  - Month-by-month card timeline showing cycle lengths, dominant symptoms, and discovered patterns.

---

## 4. Adaptive Home Screen & Time-of-Day Context
*(File: `HomeScreen.kt`)*

- **Time-of-Day Contextual Greetings & Check-In**:
  - Morning (before 12 PM): *"Good morning. You are around Day X of your cycle."*
  - Fast-action morning chips: `[Feeling good]` `[Something feels different]` `[Log period]` `[Not now]`.
  - Afternoon/Evening: Calm check-in reflecting the current phase.
- **Intelligently Layered Quick Tracking**:
  - Keep tracking neutral and comprehensive without discouraging entries.
  - Dynamic symptom suggestions based on the active cycle phase (e.g. cramps/flow during menstrual phase; cervical mucus/LH during fertile window; fatigue/mood during luteal phase).

---

## 5. Conversational Unpacking: "Ask Without Knowing What to Ask" & "Explain My Body"
*(Files: `SomethingDifferentSheet.kt`, `CareScreen.kt`, `CareAiService.kt`)*

### A. Conversational Unpacking in `SomethingDifferentSheet.kt`
- When a user types an ambiguous query (e.g. *"I'm just feeling weird today"* or *"everything feels off"*):
  - Empathic prompt: *"I can help you unpack that. Is it more physical, emotional, or just generally different?"*
  - One-tap category chips: `[Physical pain / discomfort]` `[Mood / Emotions]` `[Low Energy / Exhaustion]` `[Vaginal / Pelvic]` `[Digestive / Stomach]`.
  - Selecting a category reveals precise, structured symptom tags that map directly into verified database fields.

### B. "Explain My Body" in `CareScreen.kt`
- Dedicated structured view synthesizing:
  - 1. *What might be happening*
  - 2. *What supports that*
  - 3. *What else could explain it*
  - 4. *What you can do*
  - 5. *What to watch*
  - 6. *When to get checked*

---

## 6. End-of-Cycle Summary & Clinician-Ready Health Export
*(Files: `CycleScreen.kt`, `SettingsScreen.kt`, `CycleCareViewModel.kt`)*

- **End-of-Cycle Summary Sheet**:
  - Displays cycle length, period duration, predominant symptoms, and comparison against baseline.
- **Clinician Visit Summary (Doctor Report)**:
  - Accessible via Settings & Insights.
  - Clean, formatted patient report containing:
    - Cycle regularity & variability statistics
    - Period duration & flow characteristics
    - Pain intensity distribution (ratings out of 10)
    - Symptom frequency by cycle phase
    - Identified clinical red flags (if any)
    - Specific questions to ask her healthcare provider
  - One-tap copy/share/export.

---

## 7. Verification & Testing Plan
- Run `compile_applet` to ensure successful compilation across all Kotlin files, Compose components, and KSP Room generators.
- Run `gradle :app:testDebugUnitTest` to verify unit and Robolectric tests for persistence, calculations, and ViewModels.
- Confirm build output APK is verified.
