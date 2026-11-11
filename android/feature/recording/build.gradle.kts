plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.recording"
}

// The recording line (docs/prd.md F-UT-03, ADR 0009): records a call by merging it with the server's line, and the
// Recordings screen (list, playback, transcripts, delete).
dependencies {
    implementation(projects.core.data)
    implementation(projects.core.telephony)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
}
