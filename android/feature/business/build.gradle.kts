plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.business"
}

// Business claims and profiles (docs/prd.md F-BZ-01/02): claim a business number (code to that number, documents,
// logo), follow the review, and see a verified business's profile.
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
}
