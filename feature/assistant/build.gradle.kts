plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.najah.mealannouncer.feature.assistant"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    // Journal d'automatisation et repas favoris (annonce rapide).
    implementation(project(":core:database"))
    implementation(project(":data:openfoodfacts"))
    implementation(project(":data:mistral"))
    implementation(project(":data:nightscout"))
    // L'annonce à voix haute du total réutilise la voix déjà réglée (Voxtral ou Android).
    implementation(project(":feature:voice"))
    // L'écriture dans CamAPS FX passe par la porte du module d'intégration.
    implementation(project(":integration:camaps"))
    // L'écriture de la description dans le Carnet Glooko (mode suivi diabétologue).
    implementation(project(":integration:glookoexport"))
    // Le canal maison : le dialogue parle et écoute par l'enceinte (docs/VOIX-MAISON.md).
    api(project(":data:homeassistant"))
    // Reconnaissance hors ligne du mot-clé « miam-miam » (docs/MIAM-MIAM.md).
    implementation(libs.vosk.android)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
