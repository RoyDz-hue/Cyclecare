# Lab-variant keeps (R8-processed Rust-execution proof).
# The production rules (proguard-rules.pro) already keep the JNI bridge
# (CycleCareCoreBridge with its native entry points). The lab additionally
# keeps the explicit self-check caller so the minified lab APK retains a
# live Kotlin->JNI dispatcher path that the instrumented test can drive.
-keep class com.cyclecare.app.lab.** { *; }

# EXACT derived keeps for the androidTest DEX boundary (lab variant only).
# The androidTest build subtracts the tested app's runtime classpath from
# the test APK's packaged classes (app-provided model), so every class the
# executed lab test path needs from that shared classpath must exist in the
# app APK under its ORIGINAL name. Each entry below names its referrer and
# evidence; ci/lab-dex-closure.py fail-closed re-verifies on every build and
# enumerates any further residual for individual review (never wildcards).
# - androidx.tracing.Trace: called by androidx.test.runner.AndroidJUnitRunner
#   (runner 1.6.2 POM compile dep tracing:1.1.0); the app R8 removed it
#   (absent from mapping); matrix matrix-c6z6pqbop9i6a crashed on it at
#   AndroidJUnitRunner.onCreate.
-keep class androidx.tracing.Trace { *; }
# - kotlin.LazyKt: reachable from the executed runner/test path via
#   androidx.test.services storage (TestDirCalculator/FileTestStorage use
#   kotlin lazy); the app shrunk it (absent from the app mapping), so its
#   references from the test APK would dangle. Reviewer reachability
#   analysis 2026-11-03 (663 classes from runner/AndroidJUnit4/
#   RustLabInstrumentedTest with UI/coroutine-test cuts).
-keep class kotlin.LazyKt { *; }
