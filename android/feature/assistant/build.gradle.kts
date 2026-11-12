plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.assistant"
}

// The AI call-screening assistant (docs/prd.md F-UT-04, ADR 0010): the setup with the forwarding codes, the minutes
// this month, the live screen that follows a call through LiveKit (core:data's optional room client) and the history.
dependencies {
    implementation(projects.core.data)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
}
