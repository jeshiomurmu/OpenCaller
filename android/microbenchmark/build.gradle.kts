plugins {
    id("opencaller.android.library")
}

// AndroidX Microbenchmark of the screening engine alone (in-memory sources, no database or network), run on a device
// or emulator: ./gradlew :microbenchmark:connectedReleaseAndroidTest (docs/testing.md, Performance).
android {
    namespace = "io.github.jeshiomurmu.opencaller.microbenchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // CI runs on emulators; their numbers are indicative only (a phone's are the reference). Without the
        // androidx.benchmark Gradle plugin the test APK isn't `speed` compiled before the run (NOT-AOT-COMPILED);
        // the numbers include JIT warm-up, which the benchmark's warm-up iterations mostly absorb.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,NOT-AOT-COMPILED"
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
