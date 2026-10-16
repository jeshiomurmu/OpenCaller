plugins {
    id("opencaller.android.library")
    id("opencaller.android.compose")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)

    testImplementation(libs.junit4)
}
