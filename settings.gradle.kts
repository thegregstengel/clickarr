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

rootProject.name = "clickarr"

include(":app")
include(":core:common")
include(":core:model")
include(":core:scheduling")
include(":core:database")
include(":core:secrets")
include(":provider:api")
include(":provider:plex")
include(":provider:plex-fixtures")
include(":provider:testing")
include(":playback:core")
include(":playback:media3")
include(":data")
include(":feature:setup")
include(":feature:player")
include(":feature:channels")
include(":feature:guide")
include(":ui:design")
include(":spike")
