plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.dialer"
}

// The dial pad (docs/architecture.md §4.2). Phase 6a: number entry and Call; T9 search, the SIM picker, voicemail
// and MMI codes follow in Phase 6b.
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
}
