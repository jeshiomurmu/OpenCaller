plugins {
    id("opencaller.android.feature")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.feature.callerid"
}

// Caller ID while OpenCaller is not the default phone app: the caller card over other apps (or a heads-up
// notification), and the after-call summary (docs/architecture.md §4.1, §4.3).
dependencies {
    implementation(projects.core.telephony)
    implementation(projects.core.datastore)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
