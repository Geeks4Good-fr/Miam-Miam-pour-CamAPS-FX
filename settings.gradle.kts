// Réglages du build « Meal Announcer ».
// Repositories limités aux groupes utiles : le build échoue vite si un artefact
// est cherché au mauvais endroit, et rien d'inattendu n'est téléchargé.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
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
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    // Trouve automatiquement un JDK 17 si la machine n'en expose pas.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MealAnnouncer"

include(
    ":app",
    ":core:model",
    ":core:common",
    ":core:database",
    ":core:datastore",
    ":feature:voice",
    ":feature:meal",
    ":feature:food",
    ":feature:confirmation",
    ":feature:history",
    ":feature:settings",
    ":feature:assistant",
    ":data:openfoodfacts",
    ":data:nightscout",
    ":data:voxtral",
    ":data:mistral",
    ":data:homeassistant",
    ":integration:camaps",
    ":integration:glooko",
    ":integration:glookoexport",
)
