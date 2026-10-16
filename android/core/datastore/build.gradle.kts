plugins {
    id("opencaller.jvm.library")
    alias(libs.plugins.protobuf)
}

// Lite Java + Kotlin classes for the UserSettings proto (same setup as Now in Android's datastore-proto).
protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.datastore.core)
    api(libs.protobuf.kotlin.lite)

    testImplementation(projects.core.testing)
}
