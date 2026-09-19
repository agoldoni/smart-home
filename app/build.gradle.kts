import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Le coordinate dello stack di sviluppo, da local.properties (fuori da git).
 * Assenti, restano stringhe vuote: la build funziona lo stesso e l'app chiede
 * il broker come ha sempre fatto.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun dev(chiave: String, difetto: String = "") = localProps.getProperty(chiave, difetto)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "it.agoldoni.smarthome"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.agoldoni.smarthome"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.5.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "${System.getProperty("user.home")}/.android/release-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            // Se KEY_PASSWORD non e definito si usa KEYSTORE_PASSWORD (caso comune:
            // keystore e chiave con la stessa password).
            keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Le due build convivono sullo stesso telefono: il suffisso finisce
            // nel titolo e dice quale delle due si ha davanti.
            versionNameSuffix = "-debug"

            // La debug nasce puntata allo stack di sviluppo (devops/dev), mai a
            // casa: si sviluppa contro sette prese finte, e un comando partito
            // per sbaglio non accende niente. Sono valori *predefiniti* — valgono
            // finche' nessuno ha salvato le impostazioni su quel telefono, e non
            // sovrascrivono mai quello che hai configurato a mano.
            buildConfigField("String", "DEV_BROKER_HOST", "\"${dev("dev.broker.host")}\"")
            buildConfigField("String", "DEV_BROKER_PORT", "\"${dev("dev.broker.port", "1883")}\"")
            buildConfigField("String", "DEV_BROKER_USER", "\"${dev("dev.broker.user")}\"")
            buildConfigField("String", "DEV_BROKER_PASS", "\"${dev("dev.broker.pass")}\"")
            // Anche il prefisso dei topic: lo sviluppo vive sotto `dev/`, casa
            // sotto `casa/`. Non e' ordine, e' sicurezza — i due insiemi di topic
            // non si sovrappongono, quindi nessun comando puo' finire nel ramo
            // sbagliato nemmeno puntando il broker sbagliato.
            buildConfigField("String", "DEV_REGISTRY_PREFIX", "\"${dev("dev.registry.prefix")}\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")

            // La release non ha nessun default: il broker di casa lo si scrive a
            // mano, una volta, e non viene da un file di build di qualcun altro.
            buildConfigField("String", "DEV_BROKER_HOST", "\"\"")
            buildConfigField("String", "DEV_BROKER_PORT", "\"1883\"")
            buildConfigField("String", "DEV_BROKER_USER", "\"\"")
            buildConfigField("String", "DEV_BROKER_PASS", "\"\"")
            buildConfigField("String", "DEV_REGISTRY_PREFIX", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Genera BuildConfig, da cui la schermata legge VERSION_NAME: la versione
        // mostrata e quella impressa nell'APK, non una costante da tenere allineata
        // a mano.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Schema Room versionato: rende leggibili le migrazioni nelle diff.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.paho.mqtt.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
