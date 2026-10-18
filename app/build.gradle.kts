plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firma del release: la ponen los secretos del repo en GitHub Actions (ver README). Sin ellos, el
// release sale sin firmar y la compilacion local de debug usa la llave de debug de tu Android Studio.
val releaseKeystore: String? = System.getenv("MUVI_KEYSTORE")

android {
    namespace = "dev.muve.muvi"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.muve.muvi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", "muvi")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("MUVI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MUVI_KEY_ALIAS")
                keyPassword = System.getenv("MUVI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // la de Android Studio convive con la del release (otra llave, otro nombre)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "muvi dev")
        }
        release {
            optimization {
                enable = false
            }
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false // la libreria de Go se carga sin descomprimir (menos espacio instalado)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    // SDK de muve drive (Go, gomobile). Se actualiza con: sh scripts/actualizar-muve.sh VERSION
    implementation(files("libs/muve-android.aar"))
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
