plugins {
    id("opencaller.android.library")
    id("opencaller.android.compose")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.ui"

    // Compose semantics tests (Robolectric) read the strings.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Declares the empty activity the Compose test rule hosts its content in (merged into the unit-test manifest only).
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
