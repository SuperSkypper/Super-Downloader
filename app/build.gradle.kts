plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.superskypper.superdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.superskypper.superdownloader"
        // API 29: MediaStore.Downloads + IS_PENDING sem pedir permissao de storage.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    // x264 para re-encodar o preset WhatsApp quando o arquivo passa de 62 MB.
    // O ffmpeg-kit original foi retirado do Maven Central; este e o fork mantido
    // (SDK 35, paginas de 16 KB). min-gpl = FFmpeg minimo + x264.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9")
}
