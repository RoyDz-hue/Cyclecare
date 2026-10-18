# CycleCare UI/UX Redesign, 3D Interaction & Brand System Notes

## 1. Aesthetic Direction: "Quiet Depth & Tactile Sanctuary"

CycleCare's visual language combines editorial calm with warm, responsive micro-interactions:
- **Surface Palette**: Warm ivory (`#FBF7F4`), soft blush (`#F5ECE7`), rose clay (`#FAF2EE`), and deep plum-umber (`#13090E` / `#1D1016`) in the Admin Sanctuary PWA.
- **Accent Harmony**: Deep rose (`#8E3B56` / `#E28CA9`), botanical sage (`#50695C` / `#52B788`), and champagne gold (`#C89D54` / `#DFB76C`).
- **Progressive Disclosure**: Screens present a calm, uncrowded primary view (`HomeScreen` rhythm dial + 1-tap mood check-in; `TrackScreen` collapsible accordions with live selection count badges; `CareScreen` expandable 5-step clinical cards).

## 2. Unified Brand Identity & Vector Logo

- Replaced heavy raster logo bitmaps with a unified vector emblem across Android (`drawable/ic_cyclecare_logo.xml`, `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_background.xml`, and `mipmap-*` adaptive icons) and Admin PWA (`public/icons/favicon.svg`, `icon-192.svg`, `icon-512.svg`).
- The emblem features a deep plum-rose medallion, a champagne-gold crescent moon cradling a rose-quartz lotus droplet, and a four-point celestial star.

## 3. Interactive 3D Artifacts & Procedural Crystal Harmonic Audio

Each 3D collectible in the CycleCare Vault responds to both touch ("tap-to-dance" 360° celebratory spin + vertical bounce) and 3D drag-to-orbit gestures with specular light shifting across the surface, accompanied by its own Solfeggio harmonic frequency:

| Canonical ID | Name | Vault Price | Harmonic Pitch | Visual & Acoustic Behavior |
| :--- | :--- | :--- | :--- | :--- |
| `artifact_moon` | **Luminescent Moon** | 250 Coins | `432 Hz` | Warm lunar pearl with orbiting gold stardust; plays a 432 Hz fifth chord on tap |
| `artifact_bloom` | **Blooming Lotus** | 450 Coins | `528 Hz` | Unfolding rose lotus petals; plays a 528 Hz restorative chime on tap |
| `artifact_crystal` | **Rose Quartz Crystal** | 800 Coins | `639 Hz` | Faceted rose crystal; plays a 639 Hz heart-resonance chord on tap |
| `artifact_orbit` | **Celestial Orbit** | 1200 Coins | `741 Hz` | Dual golden rings; plays a 741 Hz astral chime on tap |
| `frame_elegant` | **Golden Pearl Frame** | 350 Coins | `852 Hz` | Gilded pearl avatar border for Guided Circles; plays an 852 Hz crown chime |

When an artifact is unlocked or received as an administrative gift, `SoundFeedbackManager.playArtifactAcquired()` synthesizes a 4-note ascending crystal arpeggio (`1.0× → 1.25× → 1.5× → 2.0×`). Users can toggle 3D harmonic sounds at any time in **Settings → Communication Tone & Sound**.
