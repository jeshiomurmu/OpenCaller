plugins {
    id("opencaller.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
