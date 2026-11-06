# Rules for the minified LAB androidTest APK only (testProguardFiles on the
# lab build type; app proguardFiles do NOT apply to test APK minification).
# Test-classpath libraries carry com.google.errorprone.annotations, which
# reference the JDK-only class javax.lang.model.element.Modifier, absent
# from android.jar. R8 full mode treats that as a missing-class error
# (minifyLabAndroidTestWithR8, build 6ae9d01b57e81944fbc19a21). The
# annotation is compile-time-only and never runs on device; suppressing the
# reference is behavior-neutral. Exact class only, no wildcards.
-dontwarn javax.lang.model.element.Modifier
# monitor:1.8.0 uses these only as RuntimeInvisibleAnnotations on Checks
# and tracing methods (javap + upstream sources verified). CanIgnoreReturnValue
# explicitly has CLASS retention; MustBeClosed uses Java's default CLASS
# retention. Neither has runtime behavior. The isolated lab graph omits the
# Espresso dependency that previously supplied error_prone_annotations.
# Local R8 9.1.31 negative/positive probe reproduces exactly these two missing
# classes, then passes with these exact rules and emits no errorprone DEX types.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
