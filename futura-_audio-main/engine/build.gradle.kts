plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wakeupmusic.engine"
    compileSdk = 36
    // NDK r28 (28.0.13004108 is r28b). Matches .github/workflows/build.yml.
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 34
        ndk {
            // arm64-v8a only, by design.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Static C++ runtime: no libc++_shared.so in the APK, so nothing unaligned is packaged.
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
