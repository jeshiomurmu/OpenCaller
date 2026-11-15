plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.scanner"
}

// The voice scan (docs/prd.md F-PR-13, ADR 0011): merges the call with the recording line, where the scanner agent
// listens to the other party for a few seconds; the in-call screen shows the result.
dependencies {
    implementation(projects.core.data)
    implementation(projects.core.telephony)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
}
