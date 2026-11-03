# ViewModel countdown-block oracle (home_anchor::compute_countdowns)

jvm_expected.txt produced by EXECUTING Probe.kt (kotlinc 2.1.0, JDK
17.0.21+9) with the decision block copied verbatim from
ui/viewmodel/CycleCareViewModel.kt @ 31b88f5 updateCalculations :491-508.
14 cases cover clamp boundaries (avg 20/50), the RAW luteal/periodDuration
quirk (8/3 and 20/12), day==ovulation / day>ovulation wrap, and null inputs.
tests/countdown_corpus.rs replays all rows.
