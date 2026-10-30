plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing is configured only when CI provides a non-empty keystore.
// Locally (or without secrets) the release APK is simply left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystoreFile: File? = keystorePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }

android {
    namespace = "com.example.wakeupmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.wakeupmusic"
        minSdk = 34          // Android 14
        targetSdk = 36       // Android 16
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The embedded fallback sound must be stored uncompressed so it can be opened as a file descriptor.
    androidResources {
        noCompress += "wav"
    }

    buildFeatures {
        compose = true
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
    // Native audio engine (C++ via :engine). Replaces the native code that used to live in :app.
    implementation(project(":engine"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose UI (Stage 0 skeleton screen).
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Stage 1 playback: media session and service. Do not add media3-exoplayer: the native engine plays.
    implementation("androidx.media3:media3-session:1.6.1")

    testImplementation("junit:junit:4.13.2")
}
