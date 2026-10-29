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
    // Matches the NDK that .github/workflows/build.yml installs.
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.example.wakeupmusic"
        minSdk = 29
        targetSdk = 34 // Changed to 34 to bypass strict Android 13+ exact alarm enforcement during builds
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Static C++ runtime: no libc++_shared.so in the APK, so nothing unaligned is packaged.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The embedded fallback sound must be stored uncompressed so it can be opened as a file descriptor.
    androidResources {
        noCompress += "wav"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
// ADD THIS BLOCK AT THE END OF THE FILE
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}