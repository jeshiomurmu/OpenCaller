plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.dialer"
}

// The dial pad (docs/architecture.md §4.2): number entry with formatting as you type, T9 search over contacts and the
// call log, the SIM picker, voicemail, carrier codes (MMI/USSD) and secret codes.
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
}
