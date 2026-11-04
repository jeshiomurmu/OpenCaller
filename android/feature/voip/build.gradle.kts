plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.voip"

    // Robolectric tests read the notification strings and the merged manifest.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// App-to-app calls (docs/prd.md F-CM-09, ADR 0007): LiveKit for the audio, Jetpack Telecom so the call behaves like
// a phone call (Bluetooth, other calls, the system's call UI). Left out of F-Droid builds (-Popencaller.voip=false):
// LiveKit ships prebuilt WebRTC and Rust libraries.
dependencies {
    implementation(projects.core.data)
    implementation(libs.livekit.android)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
