//! Economy catalog metadata + alias resolution.
//! Port of EconomyCatalog in data/economy/EconomyModels.kt (main@31b88f5).
//! OMISSION (documented): drawableRes Android resource IDs do not port; the
//! UI layer must supply its own asset mapping keyed by item id.
//! Pure data + lookup only; no purchases, no wallet effects.
use crate::kotlin_text::{kotlin_trim};

/// (id, name, category, price, description, lore, aura_color_hex,
/// is_default_unlocked, harmonic_pitch_hz) — description/lore/aura are the
/// verbatim display strings from EconomyModels.kt (main@31b88f5).
pub struct EconomyItem {
    pub id: &'static str,
    pub name: &'static str,
    pub category: ItemCategory,
    pub price: i64,
    pub description: &'static str,
    pub lore: &'static str,
    pub aura_color_hex: u32,
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
    EconomyItem { id: "cycle_coin", name: "Cycle Emblem", category: ItemCategory::Artifact, price: 0, description: "The foundational talisman of biological time and balance.", lore: "Forged with the sacred crescent, reminding you that energy waxes and wanes with grace.", aura_color_hex: 0xFFF5B82E, is_default_unlocked: true, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "artifact_moon", name: "Luminescent Moon", category: ItemCategory::Artifact, price: 250, description: "Mother-of-pearl crescent bound in hand-polished gold.", lore: "Echoes the rhythm of restorative nocturnal tides. Anchors peace during follicular renewal.", aura_color_hex: 0xFFFFF8E1, is_default_unlocked: false, harmonic_pitch_hz: 432.0 },
    EconomyItem { id: "artifact_bloom", name: "Golden Blossom", category: ItemCategory::Artifact, price: 450, description: "Velvet satin petals cradling a diamond-gold stamen core.", lore: "Unfolds when vitality crests during ovulation. Symbolizes abundant creative power.", aura_color_hex: 0xFFF8BBD0, is_default_unlocked: false, harmonic_pitch_hz: 639.0 },
    EconomyItem { id: "artifact_crystal", name: "Rose Obelisk", category: ItemCategory::Artifact, price: 800, description: "Prismatic rose quartz tower on an imperial gold crown.", lore: "Refracts somatic intuition. Clears emotional fog during the luteal descent.", aura_color_hex: 0xFFF06292, is_default_unlocked: false, harmonic_pitch_hz: 741.0 },
    EconomyItem { id: "artifact_orbit", name: "Dual Eclipse Rings", category: ItemCategory::Artifact, price: 1200, description: "Harmonic interlocking tori of brushed gold and rose sheen.", lore: "Suspended in perpetual equilibrium. Marks the union of mind, breath, and biology.", aura_color_hex: 0xFFFFD54F, is_default_unlocked: false, harmonic_pitch_hz: 852.0 },
    EconomyItem { id: "frame_elegant", name: "Imperial Pearl Halo", category: ItemCategory::Frame, price: 350, description: "Beveled gold profile adornment inlaid with natural pearls.", lore: "Encircles your presence across all guided spaces with quiet, dignified grace.", aura_color_hex: 0xFFE0A728, is_default_unlocked: false, harmonic_pitch_hz: 963.0 },
    EconomyItem { id: "avatar_aurora_3d", name: "Aurora Portrait", category: ItemCategory::AvatarArt, price: 600, description: "A warm portrait in a cosy reading nook for your profile and circles.", lore: "For the quiet reader who recharges between pages.", aura_color_hex: 0xFF2A9D8F, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_luna_3d", name: "Luna Portrait", category: ItemCategory::AvatarArt, price: 600, description: "A soft portrait with long braids and bedroom plants.", lore: "For the calm spirit who blooms in her own space.", aura_color_hex: 0xFF9575CD, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_zuri_3d", name: "Zuri Portrait", category: ItemCategory::AvatarArt, price: 750, description: "A golden kitchen portrait, included with Premium.", lore: "For the home chef whose warmth feeds everyone.", aura_color_hex: 0xFFD4A017, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_nala_3d", name: "Nala Portrait", category: ItemCategory::AvatarArt, price: 750, description: "A creative art-studio portrait with shoulder-length locs.", lore: "For the maker who turns feeling into colour.", aura_color_hex: 0xFFBF5B34, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_imani_3d", name: "Imani Portrait", category: ItemCategory::AvatarArt, price: 900, description: "A thoughtful home-library portrait with a silk-press bob.", lore: "For the reader of rooms and of people.", aura_color_hex: 0xFF6A3FA0, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
    EconomyItem { id: "avatar_amara_3d", name: "Amara Portrait", category: ItemCategory::AvatarArt, price: 900, description: "A warm cafe portrait with a defined twist-out, included with Premium.", lore: "For the friend who makes every table feel like home.", aura_color_hex: 0xFFC97B8A, is_default_unlocked: false, harmonic_pitch_hz: 528.0 },
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
