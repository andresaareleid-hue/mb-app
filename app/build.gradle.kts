plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "biz.rakendus.mb"
    compileSdk = 34
    defaultConfig {
        applicationId = "biz.rakendus.mb"
        minSdk = 24
        targetSdk = 33
        versionCode = 25
        versionName = "1.24"
    }
    signingConfigs {
        create("mb") {
            storeFile = file("mb.keystore")
            storePassword = "mbplayer"
            keyAlias = "mb"
            keyPassword = "mbplayer"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("mb") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("mb") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core:1.9.0")
    implementation("org.mapsforge:mapsforge-map-android:0.21.0")
    implementation("org.mapsforge:mapsforge-themes:0.21.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
}
