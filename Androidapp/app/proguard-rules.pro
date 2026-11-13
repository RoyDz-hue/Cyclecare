# CycleCare release ProGuard/R8 rules.

# Exact JNI names in Rust cdylib, including private native entry points.
-keep class com.cyclecare.app.rust.CycleCareCoreBridge { *; }

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

# Stateful completion JNI uses exact named entry point and callbacks. Keep only
# this bridge and private operation-local callback session; no new effect grant.
-keep class com.cyclecare.app.data.local.rust.RustTrackNative {
    native <methods>;
}
-keep class com.cyclecare.app.data.local.rust.RustCompletionStorage$Session {
    public int receipt();
    public boolean valid(int);
    public long revision(int);
    public long server(int);
    public void domain(int);
    public void revisionWrite(int,long,long);
    public void outboxWrite(int,long,long);
    public void finish(long,long);
}
# Same-connection settings-only driver callback names.
-keep class com.cyclecare.app.data.local.rust.RustSettingsStorage$Session {
    public int receipt();
    public boolean validRead(int);
    public boolean valid(int);
    public long revision(int);
    public long server(int);
    public void domain(int);
    public void revisionWrite(int,long,long);
    public void outboxWrite(int,long,long);
    public void finish();
}

# Generic bounded same-connection batch driver callback names.
-keep class com.cyclecare.app.data.local.rust.RustBatchStorage$Session {
    public int receipt();
    public boolean validRead(int);
    public boolean valid(int);
    public long revision(int);
    public long server(int);
    public void domain(int);
    public void revisionWrite(int,long,long);
    public void outboxWrite(int,long,long);
    public void finish();
}

# Same-connection ACK/retry driver, precise private callback signatures.
-keep class com.cyclecare.app.data.local.rust.RustOutboxStorage$Session {
    public void ready();
    public long base();
    public long acknowledged();
    public boolean matches();
    public int attempts();
    public void maxServer(long);
    public void deleteAcked();
    public void rebase(long);
    public boolean retryCas(int);
}

# Same-connection conflict capture private callbacks.
-keep class com.cyclecare.app.data.local.rust.RustConflictStorage$Session {
    public void ready();
    public boolean matches();
    public void latest();
    public void write();
}

# Private same-connection pull-page callbacks.
-keep class com.cyclecare.app.data.local.rust.RustRemoteStorage$Session {
    public int ready();
    public long cursor();
    public long remote(int);
    public long sequence(int);
    public void inspect(int);
    public long server(int);
    public boolean pending(int);
    public void conflict(int);
    public void prepare(int);
    public long local(int);
    public void domain(int);
    public void revision(int,long);
    public void advance(int,long);
}

# Private admitted conflict-resolution callbacks and exact typed policy exception.
-keep class com.cyclecare.app.data.local.rust.ConflictRejected {
    public <init>(java.lang.String);
}
-keep class com.cyclecare.app.data.local.rust.RustResolutionStorage$Session {
    public void ready();
    public long[] metadata();
    public boolean hashMatches();
    public void skip();
    public void prepare(long,boolean);
    public void domain();
    public void delete();
    public void revision(long);
    public void outbox();
    public void resolve();
}

# Private admitted erasure DB callbacks, no preferences or callouts in JNI.
-keep class com.cyclecare.app.data.local.rust.RustErasureStorage$Session {
    public void ready();
    public void global(int);
    public void owned(int);
    public void receipts();
    public boolean exists(int);
    public void stageDelete(int);
    public void defaults();
}
