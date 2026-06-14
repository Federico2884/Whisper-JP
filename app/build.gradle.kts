plugins {
    alias(libs.plugins.android.application)
    // Kotlin is built into AGP 9.0+ — no separate kotlin-android plugin needed.
}

android {
    namespace = "com.federico.whisperjp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.federico.whisperjp"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // sherpa-onnx ships .so for these ABIs; we target arm64 (DESIGN.md §9).
            abiFilters += listOf("arm64-v8a")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    androidResources {
        // Keep the ONNX models uncompressed so sherpa-onnx reads them from assets.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    // Streaming-ish on-device ASR (sherpa-onnx, native + Kotlin API in the AAR).
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.1")
    // On-device JA->EN translation (offline after a one-time model download).
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}