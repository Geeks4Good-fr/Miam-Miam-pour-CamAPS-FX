plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Module de pilotage de l'application Glooko : services d'accessibilité, service avant-plan
// et worker périodique. Il ne connaît pas l'interface de l'application ; il produit un
// fichier CSV que `:integration:glooko` sait déjà lire.
android {
    namespace = "com.najah.mealannouncer.integration.glookoexport"
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
    api(project(":integration:glooko"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.core.ktx)
    // Hilt exige une ComponentActivity pour une activité annotée @AndroidEntryPoint.
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
