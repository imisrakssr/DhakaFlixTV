import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val tmdbKey: String = (project.findProperty("TMDB_API_KEY") as String?)
    ?: System.getenv("TMDB_API_KEY") ?: ""
val simklId: String = (project.findProperty("SIMKL_CLIENT_ID") as String?)
    ?: System.getenv("SIMKL_CLIENT_ID") ?: ""
val ciRun: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
// Version 2 started at CI run 42 (= v2.0.0); versionCode keeps the run number so updates always go up
val appVersion: String = "2.0.${(ciRun - 42).coerceAtLeast(0)}"

android {
    namespace = "com.israk.dhakaflix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.israk.dhakaflix"
        minSdk = 23
        targetSdk = 35
        versionCode = ciRun
        versionName = appVersion
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklId\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            // A secret keystore from CI wins; otherwise the bundled one keeps
            // signatures stable so new builds install over old ones.
            val envKs = System.getenv("KEYSTORE_FILE")
            if (envKs != null && file(envKs).exists()) {
                storeFile = file(envKs)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("dhakaflix.keystore")
                storePassword = "dhakaflix123"
                keyAlias = "dhakaflix"
                keyPassword = "dhakaflix123"
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably smoother on low-end TV sticks
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // installs the Compose libraries' baseline profiles at install time: pre-compiled scrolling code, far less first-scroll jank
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Room
    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    implementation("androidx.room:room-paging:$room")
    kapt("androidx.room:room-compiler:$room")

    // Paging
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    // Networking / parsing
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR codes for "set up with your phone"
    implementation("com.google.zxing:core:3.5.3")

    // Player: ExoPlayer (Media3) + FFmpeg audio decoders + LibVLC fallback
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
