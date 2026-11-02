use cyclecare_core::economy_catalog::*;

#[test]
fn catalog_shape_matches_source() {
    assert_eq!(ALL_ITEMS.len(), 12);
    assert_eq!(ALL_ITEMS[0].id, "cycle_coin");
    assert!(ALL_ITEMS[0].is_default_unlocked);
    assert_eq!(ALL_ITEMS[0].price, 0);
    assert_eq!(ALL_ITEMS[5].id, "frame_elegant");
    assert_eq!(ALL_ITEMS[5].category, ItemCategory::Frame);
}

#[test]
fn find_item_matches_id_and_name_case_insensitively() {
    assert_eq!(find_item("CYCLE_COIN").unwrap().id, "cycle_coin");
    assert_eq!(find_item("luminescent moon").unwrap().id, "artifact_moon");
    assert_eq!(find_item("  Zuri Portrait  ").unwrap().id, "avatar_zuri_3d");
}

#[test]
fn legacy_aliases_resolve_to_current_items() {
    assert_eq!(find_item("Blooming Lotus").unwrap().id, "artifact_bloom");
    assert_eq!(find_item("Rose Quartz Crystal").unwrap().id, "artifact_crystal");
    assert_eq!(find_item("Celestial Orbit").unwrap().id, "artifact_orbit");
    assert_eq!(find_item("Dual Eclipse").unwrap().id, "artifact_orbit");
    assert_eq!(find_item("Celestial Tiara").unwrap().id, "frame_elegant");
    assert_eq!(find_item("Golden Pearl Frame").unwrap().id, "frame_elegant");
}

#[test]
fn empty_and_unknown_resolve_to_none() {
    assert!(find_item("").is_none());
    assert!(find_item("   ").is_none());
    assert!(find_item("nonexistent_item").is_none());
}

#[test]
fn unlock_normalization_falls_back_to_raw() {
    assert_eq!(normalize_unlocked_item("Golden Blossom"), "artifact_bloom");
    assert_eq!(normalize_unlocked_item("server_new_item"), "server_new_item");
}
