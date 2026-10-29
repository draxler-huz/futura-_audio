plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing is configured only when CI provides a non-empty keystore.
// Locally (or without secrets) the release APK is simply left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystoreFile: File? = keystorePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }

android {
    namespace = "com.example.wakeupmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wakeupmusic"
        minSdk = 29
        targetSdk = 32 // Changed to 32 to bypass strict Android 13+ exact alarm enforcement during builds
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}