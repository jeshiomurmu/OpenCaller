plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.family"
    // Robolectric tests read the notification strings.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Family protection (docs/prd.md F-PR-14, ADR 0008): create a group, invite, join with the consent screen, sharing
// switches, and the admin's view of members, alerts and daily counts; the admin's alert notification.
dependencies {
    implementation(projects.core.data)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
