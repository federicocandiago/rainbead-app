import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: point BLUEGLEAM_SIGNING (or the default below) to a properties file with
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val signingFile = file(System.getenv("BLUEGLEAM_SIGNING") ?: "${System.getProperty("user.home")}/.android/bluegleam-release.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "net.candiago.bluegleam"
    compileSdk {
        // Android 17: the platform is named android-37.0 (API with a minor version).
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "net.candiago.bluegleam"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // passkeys inside the WebView (WebAuthn through Android's Credential Manager)
    implementation("androidx.webkit:webkit:1.14.0")
    // notifications without Google: a check every quarter of an hour (/api/push of the Bluegleam site)
    implementation("androidx.work:work-runtime:2.10.0")
}
