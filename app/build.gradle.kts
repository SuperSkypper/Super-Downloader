plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.skypper.superdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.skypper.superdownloader"
        // API 29: MediaStore.Downloads + IS_PENDING without asking for storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // OpenDocumentTree (SAF) via registerForActivityResult
    implementation("androidx.activity:activity-ktx:1.9.3")
    // x264 to re-encode the WhatsApp preset when the file goes over 62 MB.
    // The original ffmpeg-kit was pulled from Maven Central; this is the maintained fork
    // (SDK 35, 16 KB pages). min-gpl = minimal FFmpeg + x264.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9")
}
