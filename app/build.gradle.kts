import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.najah.mealannouncer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.najah.mealannouncer"
        // Android 10 : java.time disponible sans désucrage supplémentaire.
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "0.12.0"
        vectorDrawables { useSupportLibrary = true }
        // Le Hammer et l'Ulefone sont en arm64 : sans ce filtre, sherpa-onnx et Vosk ajoutent
        // ~100 Mo de bibliothèques natives pour des processeurs que l'appli ne verra jamais.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // La clé et ses mots de passe vivent hors du dépôt. Sans eux, on retombe
        // proprement sur la signature de debug plutôt que d'échouer à la configuration.
        val propsFile = rootProject.file("keystore.properties")
        val props = if (propsFile.exists()) {
            Properties().apply { propsFile.inputStream().use { load(it) } }
        } else {
            null
        }
        val storeName = props?.getProperty("storeFile")
        val store = storeName?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        if (props != null && store != null && store.exists()) {
            val config = maybeCreate("release")
            config.storeFile = store
            config.storePassword = props.getProperty("storePassword")
            config.keyAlias = props.getProperty("keyAlias")
            config.keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 activé : l'APK est destiné à être installé, pas debuggué.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:meal"))
    implementation(project(":feature:food"))
    implementation(project(":feature:assistant"))
    implementation(project(":feature:confirmation"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    implementation(project(":data:openfoodfacts"))
    // Chronomètre des appels Mistral branché sur le journal (MealAnnouncerApp).
    implementation(project(":data:mistral"))
    implementation(project(":data:nightscout"))
    implementation(project(":data:homeassistant"))
    implementation(project(":integration:camaps"))
    implementation(project(":integration:glooko"))
    implementation(project(":integration:glookoexport"))

    // Voix locale : dictée Kroko et voix Piper (docs/VOIX-LOCALE.md). sherpa-onnx n'est pas
    // publié sur Maven Central : l'AAR officiel v1.13.8 (GitHub k2-fsa/sherpa-onnx, SHA-256
    // 633c2432…bd96) est posé dans app/libs, hors Git. La 1.13.x est voulue : la 2.0 a retiré
    // espeak-ng, indispensable aux voix Piper.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
