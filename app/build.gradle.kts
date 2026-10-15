plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python") version "16.1.0"
}

android {
    namespace = "com.panda.pandadl"
    // v3.2: compileSdk 36 porque el paquete Python de ytdlnis lo exige.
    // (En GitHub Actions no hay límite de aapt2 como en Termux.)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.panda.pandadl"
        // v3.4.3: minSdk 24 (mínimo exigido por Chaquopy 16.x).
        minSdk = 24
        targetSdk = 34
        versionCode = 76
        versionName = "3.18.3"
        // v3.4.3: Chaquopy exige abiFilters (el intérprete es nativo).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // v3.7: Sin packaging especial (no hay FFmpeg).

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Receta verificada en DriveTV: BOM 2024.12.01 + Kotlin 2.0.21.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // NOTA Termux: core-ktx 1.13.1 y activity-compose 1.9.3 porque las
    // versiones nuevas exigen compileSdk 35.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // v3.7: Sin FFmpeg (causa fallos en packageDebug). Se usa Muxer propio.

    // v3.17: Media3 Transformer (Apache 2.0, Google) para foto+audio.
    // Pipeline probado de Google: maneja escalado, formatos de color y
    // rarezas de codecs por dispositivo. Con fallback al Muxer propio.
    implementation("androidx.media3:media3-transformer:1.11.1")

    // v3.3: Chaquopy empaqueta Python + yt-dlp dentro del APK.
    // (El paquete de ytdlnis no era librería sino app separada.)
}

// v3.4.3: Chaquopy 16.1.0 (último compatible con AGP 8.13; el 12.0.0 solo
// soportaba AGP <= 7.2 y traía Python 3.8, incompatible con yt-dlp actual).
// El bloque se llama "chaquopy" (en 12.x se llamaba "python").
// yt-dlp 2026.08.19 va vendored en src/main/python (sin pip en el build,
// así el workflow no necesita Python instalado).
chaquopy {
    defaultConfig {
        // yt-dlp 2026.08.19 exige Python >= 3.9.
        version = "3.11"
    }
}
