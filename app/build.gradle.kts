plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bigoma.s"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bigoma.maps"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "8").toInt()
        versionName = "0.8"
    }

    // Urufunguzo gihamye: dufata idosiye yitwa *.keystore muri folder app
    val ksFile = projectDir.listFiles()?.firstOrNull { it.extension == "keystore" }
    signingConfigs {
        create("bigoma") {
            if (ksFile != null) {
                storeFile = ksFile
                storePassword = "bigoma123"
                keyAlias = "bigoma"
                keyPassword = "bigoma123"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (ksFile != null) {
                signingConfig = signingConfigs.getByName("bigoma")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.maplibre.gl:android-sdk:13.7.0-pre-globe.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
