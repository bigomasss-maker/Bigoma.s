plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bigoma.s"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bigoma.s"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-globe"
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
