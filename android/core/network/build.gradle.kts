plugins {
    id("opencaller.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// The OpenCaller server API (docs/architecture.md §8): Retrofit + OkHttp + kotlinx.serialization. Pure JVM, so it is
// tested with MockWebServer without Android; token storage and the base URL are supplied by the app.
dependencies {
    api(projects.core.common)
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
