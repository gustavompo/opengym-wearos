plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Shared signing identity for the openGym Wear OS pair (watch app + phone fork).
// This is the reusable Groovy snippet from keystore/README.md: it creates a signing config named
// `opengym` and assigns it to BOTH the `debug` and `release` build types. It is deliberately
// defensive — if keystore.properties is missing it does nothing at all and the module keeps
// AGP's default debug signing, so the project still builds without the shared key. Do not make
// the shared keystore a hard build requirement.
apply(from = rootProject.file("keystore.gradle"))

android {
    // The Kotlin package root. Deliberately *not* the applicationId: the namespace is ours
    // (readable, no collision with the phone app's own sources), while the applicationId below
    // is forced to match the phone app exactly.
    namespace = "ch.duartesantos.opengym.wear"
    compileSdk = 37
    defaultConfig {
        // Hard Wearable Data Layer requirement: the watch app must carry the *same*
        // applicationId and the *same* signing key as the openGym phone app — Play services
        // matches on package name AND signing key. Do not change this base value.
        //
        // The pairing rule (the base value IS the release id; the `debug` build type below
        // appends ".test", which is upstream openGym's own debug convention):
        //     release  ch.duartesantos.opengym       on both watch and phone
        //     debug    ch.duartesantos.opengym.test  on both watch and phone
        // Release is the pairing the real Galaxy Watch 5 Pro uses. Signing is wired by
        // keystore.gradle, applied just after the plugins block above.
        applicationId = "ch.duartesantos.opengym"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 on for release: the watch's CPU is the constraint, so the smaller, inlined
            // dex is worth it. The `benchmark` variant below has always run with R8 on, so this
            // is the configuration that has been measured, not a new one.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Installs as ch.duartesantos.opengym.test, mirroring openGym's own debug variant,
            // which already suffixes ".test". Without this the two debug builds have different
            // package names and the Wearable Data Layer refuses to route between them, so the
            // pair cannot be tested on emulators at all. The release applicationId is unaffected.
            applicationIdSuffix = ".test"
        }
        // Performance-measurement build: release code (R8 on, not debuggable) with the
        // same ".test" applicationId and the shared key, so it installs over the debug build Gus
        // runs and pairs with the phone's debug variant. `profileable` comes from
        // src/benchmark/AndroidManifest.xml so atrace/simpleperf can still see it.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".test"
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("opengym") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  // Installs the baseline profiles the Compose/Wear libraries ship, on first launch.
  implementation(libs.androidx.profileinstaller)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)

  // Wear OS Compose (Material 3)
  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.androidx.wear.compose.foundation)

  // Wear OS preview support (@WearPreview* annotations and WearDevices)
  implementation(libs.androidx.wear.compose.ui.tooling)
  implementation(libs.androidx.wear.tooling.preview)

  // Link wire format. JSON only — no serialization compiler plugin, so no @Serializable
  // codegen and nothing to keep in sync with the Kotlin plugin version.
  implementation(libs.kotlinx.serialization.json)

  // Wearable Data Layer — the production transport. `Wearable.getNodeClient`,
  // `getCapabilityClient()` and `getMessageClient()` all come from here. Note the group: it is
  // `com.google.android.gms:play-services-wearable`, not `androidx.wear:wearable` (no such
  // artifact) and not the deprecated Wearable Support Library.
  implementation(libs.play.services.wearable)
  // `.await()` on the GMS `Task`s above. See the comment in libs.versions.toml.
  implementation(libs.kotlinx.coroutines.play.services)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
