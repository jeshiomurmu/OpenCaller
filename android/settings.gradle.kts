pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// App calls (LiveKit, Phase 11) are left out with -Popencaller.voip=false: the F-Droid build, because LiveKit
// ships prebuilt WebRTC and Rust libraries. Flash messages and video caller ID stay in every build.
val voipEnabled = providers.gradleProperty("opencaller.voip").getOrElse("true").toBoolean()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        if (voipEnabled) {
            // JitPack serves exactly one module: the LiveKit SDK's audio-routing library, which is published nowhere
            // else (owner's decision, 2026-11-04). Nothing else may resolve from it.
            exclusiveContent {
                forRepository { maven("https://jitpack.io") { name = "JitPack" } }
                filter { includeModule("com.github.davidliu", "audioswitch") }
            }
        }
    }
}

rootProject.name = "OpenCaller"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:common")
include(":core:model")
include(":core:phonenumber")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:telephony")
include(":core:ml")
include(":core:testing")
include(":feature:onboarding")
include(":feature:callerid")
include(":feature:blocking")
include(":feature:search")
include(":feature:profile")
include(":feature:dialer")
include(":feature:calllog")
include(":feature:contacts")
include(":feature:incall")
include(":feature:messages")
include(":feature:otp")
include(":feature:business")
include(":feature:premium")
include(":feature:social")
include(":feature:backup")
if (voipEnabled) include(":feature:voip")
include(":feature:family")
include(":feature:recording")
include(":feature:assistant")
include(":feature:scanner")
include(":feature:settings")
