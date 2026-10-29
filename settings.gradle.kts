pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupBy
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven { url = uri("$rootDir/libs/local") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "jarvis"
include(":app")
include(":core")
include(":wakeword-engine")
include(":audio-engine")
include(":asr-engine")
include(":tts-engine")
include(":brain")
include(":tools")
include(":screen-control")
include(":overlay-ui")
include(":background-service")
include(":connectivity-router")
include(":permissions-onboarding")
