plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.search"
}

// Number search with a daily quota (docs/prd.md F-ID-03) and disputes of a spam label (docs/design.md §8 errors).
dependencies {
    implementation(projects.core.telephony)
    implementation(projects.core.datastore)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
