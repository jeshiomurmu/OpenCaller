plugins {
    id("opencaller.android.library")
    id("opencaller.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.jeshiomurmu.opencaller.core.ml"
}

// On-device SMS understanding (docs/architecture.md §4.4 steps 3–5): one-time codes, transactions, links and the
// smart-inbox category. Rules only for now; no trained model ships yet (ml/sms-classifier/README.md).
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The tests read the assets from the source tree and write the corpus scores (TC-OTP-001, TC-SMS-004) to a report.
tasks.withType<Test>().configureEach {
    val report = layout.buildDirectory.file("reports/corpus-metrics/$name.txt")
    systemProperty("opencaller.ml.assets", file("src/main/assets").absolutePath)
    systemProperty("opencaller.ml.report", report.get().asFile.absolutePath)
    outputs.file(report)
    testLogging.showStandardStreams = true
}

// `./gradlew :core:ml:corpusMetrics`: runs the unit tests and prints OTP precision and recall, transaction field
// accuracy and the classifier's macro-F1 on every corpus split.
tasks.register("corpusMetrics") {
    group = "verification"
    description = "Prints the SMS corpus scores (OTP extraction, transactions, classifier macro-F1)."
    dependsOn("testDebugUnitTest")
    val report = layout.buildDirectory.file("reports/corpus-metrics/testDebugUnitTest.txt")
    doLast { println(report.get().asFile.readText()) }
}
