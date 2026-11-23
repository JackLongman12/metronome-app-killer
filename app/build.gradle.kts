// ─────────────────────────────────────────────────────────────────────────────
// Build script for the app module.
// LEARN: Gradle is the build tool. This file says which Android SDK to compile
// against, the app's id and version, how to sign release builds, how to build
// the C++ engine, and which libraries to download (the `dependencies` block —
// versions live in gradle/libs.versions.toml, the "version catalog").
// ─────────────────────────────────────────────────────────────────────────────
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI passes these; local builds fall back to sensible defaults.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val githubRepo = System.getenv("GITHUB_REPOSITORY") ?: "JackLongman12/metronome-app-killer"

android {
    namespace = "com.metronomekiller.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.metronomekiller.app"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")

        // Only ship 64-bit ARM code: that's every phone from the last ~8 years,
        // and it keeps the APK small (ONNX Runtime alone is big per CPU type).
        // Debug builds also include x86_64 so they run on PC emulators.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    // LEARN: this tells Gradle to run CMake on app/src/main/cpp/CMakeLists.txt,
    // compiling the C++ audio engine into libmetronome.so inside the APK.
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            isMinifyEnabled = false
            // Falls back to the debug key locally so `assembleRelease` still works without secrets.
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // "Desugaring" back-ports newer Java library features (java.nio.file, java.time...)
        // to older Android versions. NewPipe Extractor needs it below Android 13.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Store native libraries compressed inside the APK: a much smaller download.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // "Prefab" lets C++ libraries ship inside normal Gradle dependencies;
        // it's how CMake's find_package(oboe) finds Oboe.
        prefab = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oboe)
    implementation(libs.onnxruntime.android)   // runs the beat-tracking model on the phone
    implementation(libs.media3.exoplayer)      // plays songs, with our click mixed in
    implementation(libs.newpipe.extractor)     // YouTube / SoundCloud links (GPL-3.0, like this app)
    coreLibraryDesugaring(libs.desugar.nio)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for PC-side tests
}
