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

    testImplementation(libs.junit4)
}
