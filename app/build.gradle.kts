plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.bee.chorus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bee.chorus"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Fall back to the shared debug keystore so a release build always works
            // without extra local setup.
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true      // R8: keeps the shipped APK small
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Ship English only: appcompat/core/Media3 bundle ~70 locale translations and
    // resources.arsc was one of the APK's biggest entries (AGP 9 replacement for the
    // removed resConfigs("en")).
    androidResources {
        localeFilters += "en"
    }

    packaging {
        resources {
            // Third-party licence/notice texts are legal text, never read at runtime
            // (~20 KB). Deliberately narrow: only LICENSE/NOTICE .txt under META-INF.
            excludes += setOf(
                "META-INF/**/LICENSE.txt",
                "META-INF/**/NOTICE.txt",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With built-in Kotlin, jvmTarget is derived from
    // compileOptions.targetCompatibility (17) automatically — no separate
    // kotlin { compilerOptions { jvmTarget } } block needed.
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // RecyclerView only. Intentionally NO com.google.android.material (the heaviest
    // dep) — we use framework widgets + appcompat to keep the APK and RAM small.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Playback + MediaSession (background, notification, lock-screen controls).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}