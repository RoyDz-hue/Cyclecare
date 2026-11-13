# CareScreen rules oracle

jvm_expected.txt from EXECUTING Probe.kt (kotlinc 2.1.0, JDK 17.0.21+9) with expressions copied verbatim from ui/screens/CareScreen.kt @ 31b88f5 (:165-167, :117, :206-209, :303-317, :231). tests/care_screen_corpus.rs replays all 26 rows. Includes the canSend(raw-length) vs onSend(trimmed-length) asymmetry and the whitespace-only unavailableReason (isNotBlank) case.
