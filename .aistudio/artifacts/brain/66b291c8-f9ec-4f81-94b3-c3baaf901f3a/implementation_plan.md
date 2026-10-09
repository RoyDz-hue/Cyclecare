# Onboarding Persistence in Room & First-Launch Experience

This plan establishes a dedicated Room persistence model (`OnboardingProfile` and `OnboardingProfileDao`) to store all cycle tracking answers, legal consent, and user preferences collected during onboarding, while resolving the issue that prevented the onboarding screen from displaying on the device.

---

## 1. Root Cause Analysis: Why Onboarding Was Not Showing

1. **Premature Completion Flag**: In `CycleSettings.kt`, `isOnboardingCompleted` defaulted to `true` (`val isOnboardingCompleted: Boolean = true`).
2. **Repository Pre-seeding**: In `CycleRepository.kt`, when settings were absent, it created `CycleSettings(isOnboardingCompleted = true)`.
3. **ViewModel Initial State**: `CycleUiState.showOnboarding` initialized to `false` and loaded `!settings.isOnboardingCompleted`, immediately rendering the core tab shell (`HomeScreen`) instead of `OnboardingScreen`.

### Resolution
- Update `CycleSettings.isOnboardingCompleted` to default to `false`.
- Ensure repository initial settings honor `isOnboardingCompleted = false` for fresh installations.
- When `showOnboarding == true`, the device directly renders the progressive 5-screen `OnboardingScreen`.

---

## 2. Room Architecture: Dedicated Onboarding Persistence

### A. Entity: `OnboardingProfile` (`com.example.data.local`)
Create `@Entity(tableName = "onboarding_profiles")`:
- `id: Int = 1` (`@PrimaryKey`)
- `isCompleted: Boolean` (true upon finishing)
- `completedAtTimestamp: Long` (System epoch millis)
- `isAgeVerified: Boolean` (Adult 18+ confirmation)
- `isTermsAgreed: Boolean` (Terms of Service & Privacy Policy consent)
- `accountMode: String` (`"PRIVATE"` or `"SYNC_ACCOUNT"`)
- `selectedGoalsCsv: String` (e.g. `"🌸 Understand my cycle,🩷 Track my periods"`)
- `lastPeriodDateString: String` (YYYY-MM-DD or user estimate)
- `approximatePeriodChoice: String` (`"EXACT"`, `"Today"`, `"A few days ago"`, `"About a week ago"`, etc.)
- `cycleLengthOption: String` (`"25–28 days"`, `"21–24 days"`, etc.)
- `cycleLengthDays: Int` (e.g., 28)
- `remindFertileWindow: Boolean` (User opt-in for fertile window reminders)

### B. DAO: `OnboardingProfileDao` (`com.example.data.local`)
Create `@Dao`:
- `fun getProfileFlow(): Flow<OnboardingProfile?>`: Reactive stream of the profile.
- `suspend fun getProfile(): OnboardingProfile?`: Direct fetch.
- `suspend fun insertOrUpdateProfile(profile: OnboardingProfile)`: `@Insert(onConflict = OnConflictStrategy.REPLACE)`
- `suspend fun deleteProfile()`: `@Query("DELETE FROM onboarding_profiles")`

### C. Room Database Migration (`CycleCareDatabase.kt`)
- Register `OnboardingProfile::class` in `@Database(entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class], version = 3)`.
- Expose `abstract fun onboardingDao(): OnboardingProfileDao`.

---

## 3. Repository & Data Synchronization (`CycleRepository.kt`)

1. **Expose Onboarding Profile Flow**:
   - `val onboardingProfileFlow: Flow<OnboardingProfile?> = dao.getProfileFlow()`
2. **Complete Onboarding Transaction**:
   - Save `OnboardingProfile`.
   - Update `CycleSettings`:
     - `isOnboardingCompleted = true`
     - `selectedGoalsCsv = profile.selectedGoalsCsv`
     - `lastPeriodStartDate = profile.lastPeriodDateString`
     - `averageCycleLength = profile.cycleLengthDays`
     - `remindFertile = profile.remindFertileWindow`
3. **Generate Initial Cycle Logs**:
   - Based on user answer ("Generate initial cycle data matching the user's entered period date"):
     - If user specified an exact or relative period start date, automatically populate period logs (`flowLevel = "MODERATE"`, `"HEAVY"`, etc.) for those days in `cycle_day_logs`.
     - Ensures the Home calendar and cycle wheel immediately show active or recent period data tailored to the user.

---

## 4. ViewModel & UI Integration

1. **`OnboardingViewModel.kt`**:
   - Ensure all parameters from the 5-step flow (Goals, Last Period, Cycle Length, Age Verification, Terms Agreement, Fertile Reminder) are structured into an `OnboardingProfile`.
2. **`MainActivity.kt` & `CycleCareViewModel.kt`**:
   - On fresh launch, verify `uiState.showOnboarding == true` so the user is greeted with Screen 1 (Welcome).
   - Once completed, persist to Room and transition smoothly to the main app dashboard with personalized metrics.

---

## 5. Verification Plan

1. **Compilation Check**:
   - Run `compile_applet` to verify Room entity annotation processing (KSP) and database schema compilation.
2. **Unit Tests**:
   - Add unit test coverage in `OnboardingProfileTest.kt` verifying DAO insertion, profile retrieval, and settings synchronization.
   - Run `gradle :app:testDebugUnitTest`.
3. **Device / Emulator Verification**:
   - Verify that launching the application immediately presents the CycleCare Onboarding experience on screen.
