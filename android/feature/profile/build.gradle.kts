plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.profile"
}

// Me tab: optional sign-in (number → SMS code → done), the account section, and since Phase 8 the profile: editor,
// visibility, incognito, visitors, contact requests, other people's profiles and account deletion.
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
