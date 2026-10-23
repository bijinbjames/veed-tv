import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The release key, kept outside the repo (see README, "Release key"). Every copy of the app
// handed out is signed with it, and Android only installs an update signed with the same key:
// lose it and every TV has to uninstall (losing its favourites) and start again.
val releaseKey = File(System.getProperty("user.home"), ".config/veed-tv/release.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Machine-local settings, never in git (see README, "Configuration").
val localProps = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    ?: Properties()

// The home server(s) that serve the tested lineup, guide and app updates, tried in order, comma
// separated. Without any, the app runs on its bundled lineup and refreshes links from iptv-org.
val serverUrls: String = providers.gradleProperty("veedtv.serverUrls").orNull
    ?: localProps.getProperty("veedtv.serverUrls", "")

android {
    namespace = "home.veed.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "home.veed.tv"
        // The home TV is LineageOS 18.1 = Android 11 (API 30). 25 is Fire OS 6 (Android 7.1), the
        // oldest TV a friend runs it on; below that Compose is too slow on the hardware anyway.
        minSdk = 25
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        // Where the nightly-tested lineup.json, epg.json and updates come from (veedtv.serverUrls,
        // above), the last one that worked tried first. When none answers, the app keeps using the
        // last copies it downloaded, and after a day refreshes links from iptv-org itself.
        buildConfigField("String", "SERVER_URLS", "\"$serverUrls\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The build that goes on the TVs. R8-optimised because Compose is far smoother that way
        // on weak hardware. Never signed with a debug key: such a copy could not update, or be
        // updated by, the ones already handed out.
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
