import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// Release signing. Credentials live in `keystore.properties` at the repo root
// (gitignored) alongside the keystore. When absent (fresh clone / CI without the
// key), the release build stays unsigned rather than failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Secrets that must not be committed. `.env` is gitignored (see `.env.example` for
// the template); a missing file or key yields "" so a fresh clone still builds —
// the feature that needs the key degrades instead of breaking the build.
val dotEnvFile = rootProject.file(".env")
val dotEnv = Properties().apply {
    if (dotEnvFile.exists()) dotEnvFile.inputStream().use { load(it) }
}
fun secret(name: String): String = (dotEnv[name] as String?)?.trim().orEmpty()

val geolocationApiKey = secret("GEOLOCATION_API_KEY")
if (geolocationApiKey.isEmpty()) {
    logger.warn("WARNING: GEOLOCATION_API_KEY missing from .env — device metrics will report null coordinates.")
}

// Not a secret — the committed default is the production host, so a fresh clone
// builds a working APK. `.env` only overrides it (e.g. to point at a staging host).
val metricsBaseUrl = secret("METRICS_BASE_URL").ifEmpty { "https://metrics.cyma.digital/" }

android {
    namespace = "com.cyma.videoloop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cyma.videoloop"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("String", "API_BASE_URL", "\"https://www.cymadisplay.com/api/v2/\"")
            buildConfigField("String", "METRICS_BASE_URL", "\"$metricsBaseUrl\"")
            buildConfigField("String", "GEOLOCATION_API_KEY", "\"$geolocationApiKey\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed only when keystore.properties is present; otherwise unsigned.
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
            buildConfigField("String", "API_BASE_URL", "\"https://www.cymadisplay.com/api/v2/\"")
            buildConfigField("String", "METRICS_BASE_URL", "\"$metricsBaseUrl\"")
            buildConfigField("String", "GEOLOCATION_API_KEY", "\"$geolocationApiKey\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.navigation.compose)

    // Media
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.androidx.webkit)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // WiFi provisioning
    implementation(libs.zxing.core)        // QR code generation for the setup hotspot
    implementation(libs.nanohttpd)         // captive-portal HTTP server on the local-only hotspot

    // Storage
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // JVM unit tests. Scoped to the pure string surgery in data/template — the CSS
    // scanner and the legacy-WebView decoration shim rewrite every template on every
    // box, and their branches (comment stripping, @media nesting, selector splitting)
    // are invisible in an on-device screenshot.
    testImplementation(libs.junit)
}

