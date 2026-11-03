plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.settings"
}

dependencies {
    implementation(projects.core.datastore)
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    // Ghost Call waits in WorkManager (Phase 10).
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
