plugins {
    id("opencaller.android.application")
    id("opencaller.android.compose")
    id("opencaller.android.hilt")
}

android {
    namespace = "io.github.jeshiomurmu.opencaller"

    defaultConfig {
        applicationId = "io.github.jeshiomurmu.opencaller"
        versionCode = 1
        versionName = "0.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // foss: F-Droid / GitHub Releases, no Google Play Services. play: Google Play.
    // Flavor-specific bindings live in app/src/<flavor> (docs/architecture.md §3).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.phonenumber)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.telephony)
    implementation(projects.core.ml)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.callerid)
    implementation(projects.feature.blocking)
    implementation(projects.feature.search)
    implementation(projects.feature.profile)
    implementation(projects.feature.dialer)
    implementation(projects.feature.calllog)
    implementation(projects.feature.contacts)
    implementation(projects.feature.incall)
    implementation(projects.feature.messages)
    implementation(projects.feature.otp)
    implementation(projects.feature.business)
    implementation(projects.feature.premium)
    implementation(projects.feature.social)
    implementation(projects.feature.backup)
    implementation(projects.feature.voip)
    implementation(projects.feature.family)
    implementation(projects.feature.recording)
    implementation(projects.feature.assistant)
    implementation(projects.feature.scanner)
    implementation(projects.feature.settings)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit4)
}
