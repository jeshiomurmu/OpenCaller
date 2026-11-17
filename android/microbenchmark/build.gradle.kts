plugins {
    id("opencaller.android.library")
}

// AndroidX Microbenchmark of the screening engine alone (in-memory sources, no database or network), run on a device
// or emulator: ./gradlew :microbenchmark:connectedReleaseAndroidTest (docs/testing.md, Performance).
android {
    namespace = "io.github.jeshiomurmu.opencaller.microbenchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // CI runs on emulators; their numbers are indicative only (a phone's are the reference). The test APK must
        // be `speed` compiled before the run (scripts/ci/performance.sh does it; the androidx.benchmark Gradle
        // plugin isn't used).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    // Benchmarks run against the release build type, so the test APK isn't debuggable.
    testBuildType = "release"
}

dependencies {
    androidTestImplementation(projects.core.telephony)
    androidTestImplementation(projects.core.data)
    androidTestImplementation(projects.core.model)
    androidTestImplementation(projects.core.common)
    androidTestImplementation(projects.core.phonenumber)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.benchmark.junit4)
}
