plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nakshihouse.hisab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nakshihouse.hisab"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    // Release signing comes from the environment (GitHub Actions secrets).
    // Without KEYSTORE_PATH (local builds) the release type stays unsigned —
    // local development uses the debug build anyway.
    signingConfigs {
        if (System.getenv("KEYSTORE_PATH") != null) {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
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
}

// Framework Android + Kotlin stdlib, plus AndroidX core for FileProvider
// (needed to hand the downloaded update APK to the system installer).
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
