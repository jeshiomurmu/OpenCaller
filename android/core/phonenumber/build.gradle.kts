plugins {
    id("opencaller.jvm.library")
    jacoco
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)
    implementation(libs.libphonenumber)

    testImplementation(projects.core.testing)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Phase 1 exit criterion: at least 90 % line coverage on core:phonenumber.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
