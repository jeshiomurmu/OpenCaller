plugins {
    id("opencaller.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

// Macrobenchmark (cold start, screening in the running app) and the Baseline Profile generator, run against the
// app's benchmarkRelease / nonMinifiedRelease builds on a device or emulator (docs/testing.md, Performance):
//   ./gradlew :app:generateBaselineProfile        writes app/src/main/generated/baselineProfiles
//   ./gradlew :benchmark:connectedFossBenchmarkReleaseAndroidTest \
//       -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
android {
    namespace = "io.github.jeshiomurmu.opencaller.benchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI runs on emulators; their numbers are indicative only (a phone's are the reference).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }
}

baselineProfile {
    // The CI emulator (API 36) or a phone; connected devices must be rooted or API 33+.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
