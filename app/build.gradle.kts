import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-key settings live OUTSIDE the repo. Point AOIDE_KEYSTORE_PROPERTIES at the file, or put a (gitignored)
// keystore.properties next to the root build.gradle.kts. Without it, release builds are simply left unsigned.
val keystoreProps = Properties().also { props ->
    val f = file(System.getenv("AOIDE_KEYSTORE_PROPERTIES") ?: "${rootDir}/keystore.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "io.github.lamemarine.aoide"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lamemarine.aoide"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.5.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        // Same code as release (minified) but signed with the debug key, so it can be installed over a debug build
        // for local testing. Never upload this one.
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
