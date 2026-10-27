import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma: se lee de variables de entorno (GitHub Actions) o de keystore.properties
// local. Sin ellas, Gradle usa la clave debug estándar.
fun signingValue(name: String): String? {
    System.getenv(name)?.let { if (it.isNotBlank()) return it }
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        val p = Properties().apply { f.inputStream().use { load(it) } }
        return p.getProperty(name)
    }
    return null
}

val versionCodeEnv = (System.getenv("VERSION_CODE") ?: "1").toInt()
val versionNameEnv = System.getenv("VERSION_NAME") ?: "0.1.0-dev"

android {
    namespace = "io.github.emiliounitydev.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.emiliounitydev.autoclicker"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = versionNameEnv
    }

    signingConfigs {
        create("dev") {
            val path = signingValue("DEV_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = signingValue("DEV_KEYSTORE_PASSWORD")
                keyAlias = signingValue("DEV_KEY_ALIAS")
                keyPassword = signingValue("DEV_KEY_PASSWORD")
            }
        }
        create("release") {
            val path = signingValue("RELEASE_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = signingValue("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            if (signingValue("DEV_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("dev")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (signingValue("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
