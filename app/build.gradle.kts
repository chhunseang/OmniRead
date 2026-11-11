plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.hilt.android)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "io.github.chhunseang.omniread"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "0.0.2-alpha"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Modern phones (incl. Pixel 6) are arm64; keeps the sherpa-onnx native libs small.
    ndk { abiFilters += listOf("arm64-v8a") }
  }

  // Release builds are signed only when a keystore is supplied through the environment
  // (KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD, alias "upload"). Without it the release APK
  // is left unsigned, which is what F-Droid's build server expects (it signs with its own key).
  val releaseKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile }
  // A local debug.keystore (git-ignored) keeps debug builds updatable on a test phone;
  // fresh clones fall back to the Android SDK's default debug key.
  val localDebugKeystore = file("${rootDir}/debug.keystore").takeIf { it.isFile }
  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = releaseKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    if (localDebugKeystore != null) {
      create("localDebug") {
        storeFile = localDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
    debug {
      signingConfigs.findByName("localDebug")?.let { signingConfig = it }
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
  // No encrypted Google dependency metadata in the APK or bundle (required by F-Droid)
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.window.size)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)

  // Room Database
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  "ksp"(libs.androidx.room.compiler)

  // Hilt DI & WorkManager
  implementation(libs.hilt.android)
  "ksp"(libs.hilt.compiler)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.hilt.work)
  "ksp"(libs.androidx.hilt.compiler)
  implementation(libs.androidx.work.runtime.ktx)

  // AndroidX Media3 (ExoPlayer & MediaSession)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)

  // Readium Kotlin Toolkit
  implementation(libs.readium.shared)
  implementation(libs.readium.streamer)
  implementation(libs.readium.navigator)

  // sherpa-onnx (on-device Kokoro-82M TTS), published on JitPack
  implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1@aar")
  implementation("org.apache.commons:commons-compress:1.27.1")

  // Networking, Serialization & Parsing
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  "ksp"(libs.moshi.kotlin.codegen)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.jsoup)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
