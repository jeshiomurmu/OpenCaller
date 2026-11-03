plugins {
    id("opencaller.android.library")
    id("opencaller.android.compose")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
