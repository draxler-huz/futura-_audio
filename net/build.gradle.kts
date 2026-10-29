// Placeholder. The only module allowed to contain network code (see ci/guards/network_guard.sh).
// Stage 0 adds no code here. A later stage adds the TrackFetcher implementation.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.wakeupmusic.net"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
