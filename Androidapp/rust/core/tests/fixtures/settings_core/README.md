# SettingsScreen decision-core oracle (settings_core.rs)

`jvm_expected.txt` was produced by EXECUTING `Probe.kt` (kotlinc 2.1.0,
JDK 17.0.21+9, AOSP org.json classes) on the exact functions copied from
ui/screens/SettingsScreen.kt @ 31b88f5 (boundedConflictPreview :989-1002,
SyncConflictReviewRow gates :1006-1031, cycleLengthIsSet :143-146, PIN
draft filter :680). `tests/settings_core_corpus.rs` rebuilds the identical
inputs and zips outputs line by line (`\n` escaped as `\\n` in the preview
section). Sections: 15 previews, ---gates--- + 9 rows, ---cycleLength--- +
10 rows, ---pinDraft--- + 7 rows.
