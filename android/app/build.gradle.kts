plugins {
    id("opencaller.android.application")
    id("opencaller.android.compose")
    id("opencaller.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// Server address and TLS pins come from Gradle properties (never hard-coded; docs/rules.md §2):
//   opencaller.apiBaseUrl       release builds; empty = no server (blocking works offline, online features are off)
//   opencaller.apiPins          play release builds; comma-separated "sha256/…" pins, at least two (key + backup)
//   opencaller.debugApiBaseUrl  debug builds; default: the host machine as seen from the Android emulator
val releaseApiBaseUrl: Provider<String> = providers.gradleProperty("opencaller.apiBaseUrl").orElse("")
val releaseApiPins: Provider<String> = providers.gradleProperty("opencaller.apiPins").orElse("")
val debugApiBaseUrl: Provider<String> = providers.gradleProperty("opencaller.debugApiBaseUrl").orElse("http://10.0.2.2:8080/")

// Release signing comes from the environment only (the release workflow fills it from repository secrets); the
// keystore never lives in the repository. Without it, release builds are unsigned.
val signingKeystore: Provider<String> = providers.environmentVariable("OPENCALLER_KEYSTORE_FILE")
val signingStorePassword: Provider<String> = providers.environmentVariable("OPENCALLER_KEYSTORE_PASSWORD")
val signingKeyAlias: Provider<String> = providers.environmentVariable("OPENCALLER_KEY_ALIAS")
val signingKeyPassword: Provider<String> = providers.environmentVariable("OPENCALLER_KEY_PASSWORD")

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.jeshiomurmu.opencaller"

    defaultConfig {
        applicationId = "io.github.jeshiomurmu.opencaller"
        // versionCode = MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + stage, where stage is 10 + n for alpha.n,
        // 40 + n for beta.n, 70 + n for rc.n and 99 for the final release (0.1.0-alpha → 10010, 0.1.0 → 10099).
        versionCode = 10010
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each instrumented test starts from a fresh install state (no settings, no granted permissions).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        // Robolectric tests read the merged manifest (DialerEligibilityTest).
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingKeystore.isPresent) {
            create("release") {
                storeFile = file(signingKeystore.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", quoted(debugApiBaseUrl.get()))
            buildConfigField("String", "API_PINS", quoted(""))
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "API_BASE_URL", quoted(releaseApiBaseUrl.get()))
            // Read only by the play flavor (ServerModule); foss release builds use system trust.
            buildConfigField("String", "API_PINS", quoted(releaseApiPins.get()))
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(projects.core.testing)
    // DialerEligibilityTest reads the merged manifest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}

// Release builds: the server must use HTTPS, and play release builds must pin its key plus a backup key.
val checkReleaseServerConfig =
    tasks.register("checkReleaseServerConfig") {
        val url = releaseApiBaseUrl
        doLast {
            val base = url.get().trim()
            if (base.isNotEmpty() && !base.startsWith("https://")) {
                throw GradleException("opencaller.apiBaseUrl must start with https:// in release builds")
            }
        }
    }

val checkPlayReleasePins =
    tasks.register("checkPlayReleasePins") {
        val url = releaseApiBaseUrl
        val pins = releaseApiPins
        doLast {
            val count = pins.get().split(',').count { it.trim().startsWith("sha256/") }
            if (url.get().isNotBlank() && count < 2) {
                throw GradleException("play release builds need opencaller.apiPins with at least two sha256/ pins (server key + backup)")
            }
        }
    }

tasks.configureEach {
    when (name) {
        "preFossReleaseBuild" -> dependsOn(checkReleaseServerConfig)
        "prePlayReleaseBuild" -> dependsOn(checkReleaseServerConfig, checkPlayReleasePins)
    }
}
