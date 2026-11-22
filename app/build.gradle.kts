import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Phase 3 — Ably key, baked in at build time from a GitHub Actions secret
// (ABLY_API_KEY). Never committed to git, never typed into the app.
// When the secret is absent (a local build, or before the secret is set),
// the field is empty and the app falls back to the key pasted in the
// Squad voice section.
val ablyApiKey: String = System.getenv("ABLY_API_KEY").orEmpty()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.koseiedits.squadstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koseiedits.squadstream"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0-discord-ui"
        // Baked-in Ably key (empty until the ABLY_API_KEY secret is set).
        buildConfigField("String", "ABLY_API_KEY", "\"$ablyApiKey\"")
        // WebRTC ships native libs per ABI; arm64 + armv7 cover every
        // modern phone and keep the APK from ballooning with x86 libs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // RTMP screen streaming (RootEncoder 2.8.2 via JitPack)
    implementation(libs.rootencoder.library)
    // Phase 3 — WebRTC voice room (webrtc-sdk fork, org.webrtc.* API)
    implementation(libs.webrtc.sdk)
    // Phase 3 — Ably Pub/Sub signaling (device SDK 2.0.0) + JSON for signal messages
    implementation(libs.ably.pubsub.device)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
}
