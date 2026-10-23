import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.motocom"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.motocom"
        // Launching another app on a screen of our own needs Android 10; keeping it going with
        // the phone locked, Android 13.
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Lets codigo/moto_falsa.py in through `adb forward`; never in a published build.
        val cableTests = findProperty("cableTests") == "true"
        buildConfigField("boolean", "CABLE_TESTS", cableTests.toString())
        // A test build says so, so it is never taken for the published one.
        if (cableTests) versionNameSuffix = "-prueba"
    }

    // The key that signs the published APK lives outside git, in keystore.properties next to
    // this project; without it the release build is left unsigned.
    val signing = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "CABLE_TESTS", "true")
        }
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    // Reads the QR code on the bike's screen with the camera, with no network.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
