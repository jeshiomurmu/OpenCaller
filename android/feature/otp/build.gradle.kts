plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.otp"

    // Robolectric tests read the notification strings and the merged manifest.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The OTP safety guard's screens (docs/architecture.md §4.5): the full-screen warning during a call with a stranger
// and the burst alert. The rules live in core:telephony (otp).
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
