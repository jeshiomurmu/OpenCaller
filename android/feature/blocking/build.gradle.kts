plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.blocking"
}

// Protection tab: blocking settings, the rules editor and the blocked-calls log (docs/design.md §6, §7).
dependencies {
    implementation(projects.core.telephony)
    implementation(projects.core.datastore)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.database)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
