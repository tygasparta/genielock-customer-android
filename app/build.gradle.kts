import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from (in priority order):
//  1. keystore.properties in the project root (local dev only — gitignored, never committed)
//  2. environment variables RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
//     (set by .github/workflows/android.yml from GitHub secrets)
// If neither is present, release builds fall back to the debug keystore so `assembleRelease`
// still works for local testing without secrets. CI always has the env vars set.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasLocalKeystoreProps = keystorePropertiesFile.exists()
if (hasLocalKeystoreProps) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun signingProp(key: String, envKey: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(envKey)

val releaseStoreFilePath = signingProp("storeFile", "RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStoreFilePath != null && rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "org.gadgetgenie.customer"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.gadgetgenie.my.native"
        minSdk = 26
        // targetSdk pinned to 34 (not 35) deliberately: Android 15 (API 35) enforces
        // edge-to-edge for apps that target it, which makes Window.setStatusBarColor /
        // setNavigationBarColor no-ops — breaking the fixed blue-status/white-nav branding
        // this lock-screen flow depends on. compileSdk stays on the latest stable (35).
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = signingProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing config found (keystore.properties or RELEASE_* env vars). " +
                        "Falling back to the debug keystore — fine for local testing, " +
                        "but CI must supply real secrets for a store-ready build."
                )
                signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
}
