plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.premium"
}

// Supporter tier (docs/prd.md F-UT-08): status, plans with the store's prices, purchase, restore, donation link.
dependencies {
    implementation(projects.core.data)
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
}
