plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.najah.mealannouncer.data.homeassistant"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    // Les réglages de la voix de la maison (adresse, interrupteur, jeton) que HomeLink suit.
    api(project(":core:datastore"))

    implementation(libs.kotlinx.coroutines.android)
    // Android n'a pas de client WebSocket intégré : OkHttp ne sert qu'au transport réel,
    // le protocole Home Assistant est éprouvé avec un faux transport (docs/VOIX-MAISON.md § 6).
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // Vrai org.json côté JVM : le stub d'android.jar ne fait que lever « Stub! ».
    testImplementation(libs.json)
}
