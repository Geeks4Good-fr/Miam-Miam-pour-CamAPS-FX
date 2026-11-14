// Build racine : aucun plugin n'est appliqué ici, seulement déclaré.
// Chaque module choisit les plugins dont il a besoin, avec la version du catalogue.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
