plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val whisperModel = providers.gradleProperty("whisperModel").getOrElse("ggml-small-q5_1.bin")
val vadModel = providers.gradleProperty("vadModel").getOrElse("ggml-silero-v6.2.0.bin")

android {
    namespace = "com.lyb.mysecretary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lyb.mysecretary"
        // Galaxy S26 ships with Android 16; API 35 lets us use the mediaProcessing FGS type directly.
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "WHISPER_MODEL", "\"$whisperModel\"")
        buildConfigField("String", "VAD_MODEL", "\"$vadModel\"")

        ndk {
            // Exynos 2600 is arm64 only; skipping other ABIs keeps the APK small.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload build: sign release with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // Model files are streamed from the APK; storing them uncompressed avoids inflate cost.
        noCompress += listOf("bin")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device Gemini Nano via AICore (no model inside the APK).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")

    testImplementation("junit:junit:4.13.2")
}
