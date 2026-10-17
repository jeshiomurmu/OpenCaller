plugins {
    id("opencaller.android.library")
    id("opencaller.android.hilt")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.data"
}

// Repositories over the encrypted database and the server API (docs/architecture.md §3): the only module that
// depends on both core:database and core:network. Background work (list sync, report upload) runs in WorkManager.
dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(projects.core.network)
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.work.runtime)
    implementation(projects.core.database)
    implementation(projects.core.phonenumber)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
