// Paparazzi is test-only and requires JVM 21+ at configuration time. Production
// builds document JDK 17 (BUILD-RECOVERY.md), so the plugin is resolved and
// applied only when -Ppaparazzi is passed; screenshot runs then require JDK 21.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    if (project.hasProperty("paparazzi")) {
        dependencies {
            classpath("app.cash.paparazzi:paparazzi-gradle-plugin:2.0.0-alpha05")
        }
    }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

if (project.hasProperty("paparazzi")) {
    apply(plugin = "app.cash.paparazzi")
}

android {
  namespace = "com.cyclecare.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.cyclecare.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 7
    versionName = "1.1"
    buildConfigField("String", "DEBUG_BRIDGE_URL", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Instrumented-test build type stays "debug" by default (device-verify
  // workflow). The Rust lab workflow selects the lab build type with
  // ./gradlew -Pcyclecare.testBuildType=lab ... connectedLabAndroidTest.
  testBuildType = providers.gradleProperty("cyclecare.testBuildType").orElse("debug").get()

  signingConfigs {
    create("fixedDebug") {
      storeFile = file(System.getenv("CYCLECARE_DEBUG_KEYSTORE") ?: "${rootDir}/.private/cyclecare-debug.jks")
      storePassword = System.getenv("CYCLECARE_DEBUG_STORE_PASSWORD")
      keyAlias = "cyclecare-debug"
      keyPassword = System.getenv("CYCLECARE_DEBUG_KEY_PASSWORD")
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  splits {
    abi {
      // Only active for -PslimApk sharing builds; normal builds stay universal.
      isEnable = project.hasProperty("slimApk")
      reset()
      include("arm64-v8a")
      isUniversalApk = false
    }
  }

  sourceSets {
    // Transitional Rust-core slice: the cdylib built in Androidapp/rust/core-jni
    // is copied into jniLibs/<abi>/libcyclecare_core.so by the packageRustCore
    // task. When no .so exists the app packages without it and
    // CycleCareCoreBridge falls back to the Kotlin implementations.
    getByName("main") { jniLibs.srcDir("$rootDir/rust/core-jni/jniLibs") }
  }

  buildTypes {
    release {
      buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", "false")
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Separate install/data namespace: this debug build won't replace the production app.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", "true")
      // No bridge endpoint is committed to the repo. Developers pass their own
      // single-user bridge via: ./gradlew -Pcyclecare.debugBridgeUrl=https://...
      // The previously committed *.us2.manus.computer endpoint must be retired.
      buildConfigField(
        "String",
        "DEBUG_BRIDGE_URL",
        "\"" + providers.gradleProperty("cyclecare.debugBridgeUrl").orElse("").get() + "\""
      )
      // Uses default Android auto-generated debug keystore
    }
    // Slim sharing build: minified debug-signed APK for direct install.
    // Build with: ./gradlew assembleSlim -PslimApk  (arm64-v8a only, ~half the size)
    create("slim") {
      initWith(getByName("debug"))
      val slimKeystore =
          file(System.getenv("CYCLECARE_DEBUG_KEYSTORE") ?: "${rootDir}/.private/cyclecare-debug.jks")
      if (slimKeystore.exists()) {
        signingConfig = signingConfigs.getByName("fixedDebug")
      } else {
        // CI builds leave the APK unsigned; sign locally with apksigner using the vault keystore.
        signingConfig = null
      }
      // Sharing builds stay non-debuggable: AGP compiles debuggable variants
      // through R8's debug mode, which silently disables obfuscation.
      isDebuggable = false
      buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", "true")
      // Local sandboxes that cannot run R8 can build with -PnoMinify (larger APK, same features).
      val noMinify = project.hasProperty("noMinify")
      isMinifyEnabled = !noMinify
      isShrinkResources = !noMinify
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    // Rust-execution lab: R8-processed like slim, but with an explicit lab
    // caller path (com.cyclecare.app.lab.RustLabSelfCheck, kept via
    // proguard-lab.pro) so the packaged native dispatcher is actually
    // exercised. Disposable identity and cert, emulator/device-test only,
    // never distributed. Does NOT enable any production Rust flag; the UI
    // caller adapters stay const-false here too.
    create("lab") {
      initWith(getByName("slim"))
      applicationIdSuffix = ".lab" // com.cyclecare.app.lab - isolated data namespace
      versionNameSuffix = "-lab"
      // Non-debuggable like slim so R8 runs in full mode (repo rule: AGP
      // compiles debuggable variants through R8 debug mode, which silently
      // disables obfuscation). Instrumentation does not need debuggable:
      // the test APK shares the same debug signing cert.
      isDebuggable = false
      // Disposable signing: AGP auto-generated debug keystore (~/.android),
      // never the release/upload key, never a committed keystore.
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles("proguard-lab.pro")
      // The lab androidTest APK stays R8-processed: in application modules
      // the androidTest shrinker follows the build type's isMinifyEnabled
      // (AGP 9.1.1 OptimizationDslInfoImpl.codeShrinkerEnabled; the
      // androidTest{enableMinification} DSL exists only on LibraryBuildType).
      // The app/test DEX boundary is instead closed by EXACT derived keeps:
      // proguard-lab.pro keeps, under original names, precisely the classes
      // the selected lab test needs from the shared app classpath (classes
      // the androidTest build subtracts from its program inputs as
      // app-provided - kotlin-stdlib, androidx.tracing), while
      // proguard-lab-androidtest.pro carries the test-side rules. The lab
      // test source set is isolated (src/androidTestLab only) and UI-test
      // dependencies are debug-only, so the boundary surface is minimal and
      // ci/lab-dex-closure.py fail-closed verifies it on every build.
      testProguardFiles("proguard-lab-androidtest.pro")
      buildConfigField("boolean", "RUST_LAB", "true")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Only public client configuration is allowed in the Android BuildConfig.
secrets {
  propertiesFileName = ".env.android"
  defaultPropertiesFileName = ".env.android.example"
}

dependencies {
  coreLibraryDesugaring(libs.android.tools.desugar.jdk.libs)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.net.zetetic.sqlcipher.android) {
    // Preserve the original 4.19.1@aar artifact-only dependency contract.
    isTransitive = false
    artifact { type = "aar"; extension = "aar" }
  }
  implementation(libs.androidx.sqlite)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.security.crypto)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // Library only: puts Paparazzi classes on the unit-test classpath so plain
  // testDebugUnitTest compiles on documented JDK 17. The plugin (which needs
  // JVM 21 at configuration) is still applied only with -Ppaparazzi.
  testImplementation("app.cash.paparazzi:paparazzi:2.0.0-alpha05")
  testImplementation(libs.mockwebserver)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  // UI-test-only dependencies are debug-scoped: the isolated lab test
  // (src/androidTestLab) uses only runner + androidx.test.ext:junit, and
  // every androidTest dependency overlapping the app's runtime classpath is
  // subtracted from the test APK's packaged classes (app-provided model),
  // so the lab test classpath stays minimal on purpose.
  "androidTestDebugImplementation"(platform(libs.androidx.compose.bom))
  "androidTestDebugImplementation"(libs.androidx.compose.ui.test.junit4)
  "androidTestDebugImplementation"(libs.androidx.espresso.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // Test-source KSP for RoomHostProbeDatabase (Room boundary host evidence).
  "kspTest"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}



// Paparazzi < 2.0 stable uses Gradle internals that moved in Gradle 9 (AGP 9);
// Google's AGP-9 migration note requires disabling HTML test reports as the workaround.
tasks.withType<Test>().configureEach {
    reports.html.required = false
    // The build sandbox has ~2GB RAM total: cap the test fork so the Gradle
    // daemon and the screenshot renderer do not exhaust memory together.
    maxHeapSize = "448m"
    jvmArgs("-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=256m")
    // layoutlib holds native image buffers; a fresh JVM per test class stops
    // them accumulating across the render matrix in this 2GB sandbox.
    forkEvery = 1
}

// Copies cargo-built cyclecare-core cdylibs into jniLibs/<abi>/ for packaging.
// Build the .so first (outside Gradle):
//   cd rust/core-jni && cargo build --release --target aarch64-linux-android
// ABI map: aarch64-linux-android -> arm64-v8a, armv7-linux-androideabi ->
// armeabi-v7a, i686-linux-android -> x86, x86_64-linux-android -> x86_64.
// Missing targets are skipped, never errors: packaging proceeds Kotlin-only.
val rustAbiMap = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "armv7-linux-androideabi" to "armeabi-v7a",
    "i686-linux-android" to "x86",
    "x86_64-linux-android" to "x86_64",
)
tasks.register("packageRustCore") {
    doLast {
        rustAbiMap.forEach { (triple, abi) ->
            // The migration owner's FFI crate artifact, packaged under the
            // single runtime name libcyclecare_core.so (rust/HOST-INTEGRATION.md).
            // core-ffi >= v6 emits libcyclecare_core.so ([lib] name); older
            // artifacts emit libcyclecare_core_ffi.so. Accept both.
            val so = listOf("libcyclecare_core.so", "libcyclecare_core_ffi.so")
                .map { File("$rootDir/rust/core-ffi/target/$triple/release/$it") }
                .firstOrNull { it.exists() }
            if (so != null) {
                val outDir = File("$rootDir/rust/core-jni/jniLibs/$abi")
                outDir.mkdirs()
                val out = File(outDir, "libcyclecare_core.so")
                so.copyTo(out, overwrite = true)
                logger.lifecycle("packaged Rust core for $abi")
            } else {
                logger.info("no Rust core cdylib for $triple; Kotlin fallback will be used")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn("packageRustCore") }

// Disposable lab ONLY. AGP 9.1.1 independently runs L8 for app and androidTest;
// independent short-name assignment produced a confirmed interface/class
// collision under ART. Keep semantic desugar-library names on BOTH outputs.
// The test copy is complete so test-first class loading cannot shadow an app
// class with a member-stripped subset. App L8 still shrinks; real app R8 and
// resource shrinking remain enabled. No production variant is changed.
// INTERNAL API, intentionally pinned and fail-closed. Re-review on AGP change.
if (providers.gradleProperty("cyclecare.testBuildType").orNull == "lab") {
gradle.projectsEvaluated {
    check(com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION == "9.1.1") {
        "Lab L8 compatibility configuration requires reviewed AGP 9.1.1"
    }
    val labL8Tasks = listOf("l8DexDesugarLibLab", "l8DexDesugarLibLabAndroidTest")
    labL8Tasks.forEach { taskName ->
        val l8 = tasks.named<com.android.build.gradle.internal.tasks.L8DexDesugarLibTask>(taskName).get()
        l8.keepRulesConfigurations.addAll(listOf("-dontobfuscate", "-dontoptimize"))
        if (taskName == "l8DexDesugarLibLabAndroidTest") {
            l8.keepRulesConfigurations.add("-dontshrink")
        }
        l8.doFirst {
            logger.lifecycle("LAB_L8_FLAGS task=$taskName minSdk=${l8.minSdkVersion.get()} rules=${l8.keepRulesConfigurations.get()}")
        }
        check(l8.minSdkVersion.get() == 24)
        check(l8.keepRulesConfigurations.get().containsAll(listOf("-dontobfuscate", "-dontoptimize")))
        check(("-dontshrink" in l8.keepRulesConfigurations.get()) == (taskName == "l8DexDesugarLibLabAndroidTest"))
    }
    check(android.buildTypes.getByName("lab").isMinifyEnabled)
    check(android.buildTypes.getByName("lab").isShrinkResources)
    logger.lifecycle("LAB_L8_SCOPE property=lab AGP=9.1.1 appR8=true resourceShrink=true minSdk=24 productionVariantsUnchanged=true")
}
}
