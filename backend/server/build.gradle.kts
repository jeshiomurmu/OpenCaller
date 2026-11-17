import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "io.github.jeshiomurmu.opencaller"
version = "0.0.0"

application {
    mainClass.set("io.github.jeshiomurmu.opencaller.server.ApplicationKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.lettuce.core)
    implementation(libs.libphonenumber)
    // The JSON log encoder (logging/JsonLogEncoder.kt) extends Logback classes, so Logback is a compile dependency.
    implementation(libs.logback.classic)
    // Compile scope: PostgreSQL enum columns are written as PGobject (db/PgEnum.kt).
    implementation(libs.postgresql)
    // Ktor's JWT support brings Jackson and Guava (java-jwt, jwks-rsa); raised past the versions with known
    // advisories (scripts/ci/dependency_audit.py).
    implementation(platform(libs.jackson.bom))
    constraints {
        implementation(libs.guava) { because("jwks-rsa 0.24.1 brings 33.6.0-jre (GHSA-xxph-c9ww-hj94)") }
    }

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.tink.apps.webpush)
}

// backend/server/seed/*.csv is packaged as classpath resources under seed/ (sync/SeedData.kt).
tasks.processResources {
    from("seed") {
        include("*.csv")
        into("seed")
    }
}

tasks.test {
    // Integration tests start PostgreSQL and Redis with Testcontainers (Docker required).
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    kotlin {
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
}
