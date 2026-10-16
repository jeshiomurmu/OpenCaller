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
    api(projects.core.common)
    api(projects.core.data)
    api(projects.core.phonenumber)
    api(libs.androidx.lifecycle.common)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.core.datastore)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
