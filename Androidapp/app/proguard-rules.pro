# CycleCare release ProGuard/R8 rules.

# Keep stack traces readable enough to diagnose production crashes while
# still obfuscating class/member names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin / metadata needed by reflection-based libraries.
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-keep class kotlin.Metadata { *; }

# Moshi: models and generated/reflective JSON adapters.
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Retrofit / OkHttp / OkIo (explicit; their consumer rules also apply).
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Room / SQLCipher native bridge.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Data classes deserialized by Moshi must keep their property names.
-keepnames class com.cyclecare.app.data.model.** { *; }
-keepnames class com.cyclecare.app.data.economy.** { *; }
-keepnames class com.cyclecare.app.data.payment.** { *; }
-keepnames class com.cyclecare.app.data.notification.** { *; }
-keepnames class com.cyclecare.app.data.supabase.** { *; }

# Domain engines run reflectively in diagnostics/tests; keep names stable.
-keepnames class com.cyclecare.app.domain.** { *; }

# Tink references error_prone annotations that are compile-time only.
-dontwarn com.google.errorprone.annotations.**
