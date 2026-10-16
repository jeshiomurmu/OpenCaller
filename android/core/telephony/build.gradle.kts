plugins {
    id("opencaller.android.library")
    id("opencaller.android.hilt")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.telephony"

    // Robolectric reads the merged manifest to resolve this module's own components.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.lifecycle.common)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
