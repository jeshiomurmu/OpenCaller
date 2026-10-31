plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.messages"

    // Robolectric tests read the notification strings and the merged manifest.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The default SMS app (docs/architecture.md §4.4): the role's receivers and service, Android's message store,
// sending and downloading through SmsManager, notifications and the conversation screens.
dependencies {
    implementation(projects.core.telephony)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
