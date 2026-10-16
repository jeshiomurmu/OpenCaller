plugins {
    id("opencaller.android.library")
    id("opencaller.android.hilt")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.data"
}

// Repositories over the encrypted database (docs/architecture.md §3). No network yet: reports stay in the outbox.
dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.core.database)
    implementation(libs.androidx.room.runtime)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
