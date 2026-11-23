pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds libraries straight from GitHub; NewPipe Extractor is published there.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com\.github\..*""") }
        }
    }
}

rootProject.name = "metronome-app-killer"
include(":app")
