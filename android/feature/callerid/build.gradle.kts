plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.callerid"

    // Robolectric tests read the notification and caller card strings.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Caller ID while OpenCaller is not the default phone app: the caller card over other apps (or a heads-up
// notification); the after-call summary, as a notification or (default phone app) a screen (docs/architecture.md
// §4.1, §4.3).
dependencies {
    implementation(projects.core.telephony)
    implementation(projects.core.datastore)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
