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
    versionCode = 4
    versionName = "1.1"
    buildConfigField("String", "DEBUG_BRIDGE_URL", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

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
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
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
