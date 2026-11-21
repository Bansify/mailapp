pluginManagement {
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
        google()
        mavenCentral()
        // Bansify Accounts shared SDK (:core + :ui) is published from Bansify/accountsapp tags.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.Bansify.*") }
        }
    }
}

rootProject.name = "MailApp"
include(":app")
