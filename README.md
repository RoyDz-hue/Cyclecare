# CycleCare Monorepo

CycleCare is an offline-first menstrual-health tracking and wellness platform featuring local cycle pattern analysis, clinical safety boundaries, and community support.

This repository is organized into two primary applications:

```
Cyclecare/
├── Androidapp/     # Native Android client (Jetpack Compose, Room v8, SQLCipher, Supabase backend)
└── AdminPWA/       # Admin Progressive Web App (React, Vite, Tailwind CSS, moderation & telemetry)
```

---

## 📱 Android Application (`Androidapp/`)

Native Android client built with Kotlin, Jetpack Compose, Material Design 3, Room v8 encrypted with SQLCipher via Android Keystore, and offline-first intelligence engines.

### Directory Overview
- `app/` — Main Android application source code, UI screens, domain engines, and repositories.
- `gradle/`, `gradlew`, `build.gradle.kts`, `settings.gradle.kts` — Gradle build configuration.
- `supabase/` — Canonical database migrations (PostgreSQL/RLS) and serverless Edge Functions.
- `docs/` — Architecture specifications, clinical acceptance criteria, and audit reports.

### Build and Test
```bash
cd Androidapp

# Run local unit tests
./gradlew :app:testDebugUnitTest

# Assemble debug build
./gradlew :app:assembleDebug
```

---

## 💻 Admin PWA (`AdminPWA/`)

A Progressive Web App (PWA) control center for system administration, feature flag management, clinical safety audit logs, and circle chat moderation.

### Directory Overview
- `src/` — React components, state management, and Supabase client integration.
- `public/` — PWA manifest, service worker (`sw.js`), and asset icons.
- `test/` — Session concurrency and hosting contract tests.

### Build and Test
```bash
cd AdminPWA

# Install dependencies
npm install

# Run development server
npm run dev

# Production build
npm run build
```

---

## 🔒 Security & Privacy Invariants

- **No Diagnostic Claims:** CycleCare is not a medical device, contraceptive, or diagnostic tool.
- **Local-First Privacy:** Health metrics are encrypted locally with SQLCipher using keys wrapped by the hardware-backed Android Keystore.
- **Server Authority:** Financial ledger transactions, provider keys, and moderation permissions are enforced strictly by Supabase PostgreSQL RLS and authenticated Edge Functions. Client applications never hold service-role keys.
