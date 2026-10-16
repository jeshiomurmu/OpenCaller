plugins {
    id("opencaller.jvm.library")
}

// Test utilities shared by every module's tests: rules, fakes and fixtures (docs/testing.md §3).
dependencies {
    api(projects.core.common)
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
}
