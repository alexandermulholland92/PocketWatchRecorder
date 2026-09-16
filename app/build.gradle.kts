import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Build-time configuration, read from the (gitignored) local.properties.
 * See local.properties.example for the keys this expects.
 */
val localProperties = Properties().apply {
    val file = project.rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.pocket.watchrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pocket.watchrecorder"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // The base URL used to be injected here too, but PocketNetwork.kt has
        // always hardcoded it — the field only added a way for it to arrive
        // empty, so it is gone.
        buildConfigField("String", "POCKET_API_KEY", "\"${localProperty("POCKET_API_KEY") ?: ""}\"")
    }

    signingConfigs {
        // Optional: only wired up when local.properties points at a keystore,
        // so a clean checkout still builds without one.
        val storePath = localProperty("RELEASE_STORE_FILE")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = localProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Wear APKs are installed over Bluetooth and live on a device with
            // very little storage, so shrinking is worth more here than usual.
            // Keep rules live in src/main/keepRules/.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The JVM tests here cover pure logic, but the classes they load carry
        // Android types in their signatures; default values keep android.jar's
        // stubs from throwing on anything incidental.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(platform(libs.compose.bom))

    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    implementation(libs.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
