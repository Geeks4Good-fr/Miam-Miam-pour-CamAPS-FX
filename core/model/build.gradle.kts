plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Module de domaine : Kotlin pur, aucune dépendance Android ni réseau.
// C'est ici que vivent les règles de calcul des glucides, testables sans appareil.

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
