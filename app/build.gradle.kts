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
        versionCode = 18
        versionName = "0.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only: the x86 copies of the native libraries are for emulators.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The app speaks Spanish and English; the libraries bring dozens more.
    androidResources { localeFilters += listOf("es", "en") }
    // The language is chosen inside the app, so every one must be installed, not only the phone's.
    bundle { language { enableSplit = false } }
    // BouncyCastle's post-quantum tables: nothing in the app or in ADB uses them.
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "org/bouncycastle/pqc/**") }
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
