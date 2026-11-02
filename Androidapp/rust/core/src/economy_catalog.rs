//! Economy catalog metadata + alias resolution.
//! Port of EconomyCatalog in data/economy/EconomyModels.kt (main@31b88f5).
//! OMISSION (documented): drawableRes Android resource IDs do not port; the
//! UI layer must supply its own asset mapping keyed by item id.
//! Pure data + lookup only; no purchases, no wallet effects.
use crate::kotlin_text::{kotlin_trim};

/// (id, name, category, price, is_default_unlocked, harmonic_pitch_hz)
pub struct EconomyItem {
    pub id: &'static str,
    pub name: &'static str,
    pub category: ItemCategory,
    pub price: i64,
    pub is_default_unlocked: bool,
    pub harmonic_pitch_hz: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ItemCategory {
    Artifact,
    Frame,
    Background,
    Theme,
    AvatarArt,
    Badge,
}

/// Item order preserved from ALL_ITEMS.
pub const ALL_ITEMS: &[EconomyItem] = &[
    EconomyItem { id: "cycle_coin", name: "Cycle Emblem", category: ItemCategory::Artifact, price: 0, is_default_unlocked: true, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "artifact_moon", name: "Luminescent Moon", category: ItemCategory::Artifact, price: 250, is_default_unlocked: false, harmonic_pitch_hz: 432.0 },
    EconomyItem { id: "artifact_bloom", name: "Golden Blossom", category: ItemCategory::Artifact, price: 450, is_default_unlocked: false, harmonic_pitch_hz: 639.0 },
    EconomyItem { id: "artifact_crystal", name: "Rose Obelisk", category: ItemCategory::Artifact, price: 800, is_default_unlocked: false, harmonic_pitch_hz: 741.0 },
    EconomyItem { id: "artifact_orbit", name: "Dual Eclipse Rings", category: ItemCategory::Artifact, price: 1200, is_default_unlocked: false, harmonic_pitch_hz: 852.0 },
    EconomyItem { id: "frame_elegant", name: "Imperial Pearl Halo", category: ItemCategory::Frame, price: 350, is_default_unlocked: false, harmonic_pitch_hz: 963.0 },
    EconomyItem { id: "avatar_aurora_3d", name: "Aurora Portrait", category: ItemCategory::AvatarArt, price: 600, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_luna_3d", name: "Luna Portrait", category: ItemCategory::AvatarArt, price: 600, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_zuri_3d", name: "Zuri Portrait", category: ItemCategory::AvatarArt, price: 750, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_nala_3d", name: "Nala Portrait", category: ItemCategory::AvatarArt, price: 750, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_imani_3d", name: "Imani Portrait", category: ItemCategory::AvatarArt, price: 900, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_amara_3d", name: "Amara Portrait", category: ItemCategory::AvatarArt, price: 900, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
];

/// findItem: trim; empty -> None; match id or name case-insensitively, then
/// the verbatim legacy alias table (server-side renames stay resolvable).
pub fn find_item(id_or_name: &str) -> Option<&'static EconomyItem> {
    let clean = kotlin_trim(id_or_name);
    if clean.is_empty() {
        return None;
    }
    ALL_ITEMS.iter().find(|it| {
        it.id.eq_ignore_ascii_case(clean)
            || it.name.eq_ignore_ascii_case(clean)
            || (it.id == "artifact_bloom"
                && (clean.eq_ignore_ascii_case("Golden Blossom")
                    || clean.eq_ignore_ascii_case("Blooming Lotus")))
            || (it.id == "artifact_crystal"
                && (clean.eq_ignore_ascii_case("Rose Obelisk")
                    || clean.eq_ignore_ascii_case("Rose Quartz Crystal")))
            || (it.id == "artifact_orbit"
                && (clean.eq_ignore_ascii_case("Dual Eclipse")
                    || clean.eq_ignore_ascii_case("Dual Eclipse Rings")
                    || clean.eq_ignore_ascii_case("Celestial Orbit")))
            || (it.id == "frame_elegant"
                && (clean.eq_ignore_ascii_case("Celestial Tiara")
                    || clean.eq_ignore_ascii_case("Imperial Pearl Halo")
                    || clean.eq_ignore_ascii_case("Golden Pearl Frame")))
    })
}

/// normalize_unlocked_item: the unlocked_items row contract from
/// SupabaseEconomyService - raw string -> catalog id when resolvable,
/// else the raw string preserved.
pub fn normalize_unlocked_item(raw: &str) -> String {
    find_item(raw).map(|i| i.id.to_string()).unwrap_or_else(|| raw.to_string())
}
