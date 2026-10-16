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
include(":feature:voip")
include(":feature:family")
include(":feature:recording")
include(":feature:assistant")
include(":feature:scanner")
include(":feature:settings")
