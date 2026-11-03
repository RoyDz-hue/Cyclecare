# Lab-variant keeps (R8-processed Rust-execution proof).
# The production rules (proguard-rules.pro) already keep the JNI bridge
# (CycleCareCoreBridge with its native entry points). The lab additionally
# keeps the explicit self-check caller so the minified lab APK retains a
# live Kotlin->JNI dispatcher path that the instrumented test can drive.
-keep class com.cyclecare.app.lab.** { *; }
