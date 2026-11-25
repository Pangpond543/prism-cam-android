import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Prism signing key lives outside the repo: %USERPROFILE%\PrismKeys (docs/pitfalls.md "Signing").
val keyDir = File(System.getProperty("user.home"), "PrismKeys")
val keyProps = Properties().apply {
    val f = File(keyDir, "prism-cam-android.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.prism.cam"
    // AndroidX 2026.11 libraries need compileSdk ≥ 37.1 (compile only — targetSdk stays 36 = Android 16 behaviour).
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.prism.cam"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "2.0.0"
        // phones and tablets only (the x86 builds of the AI library alone were ~43 MB)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("prism") {
            if (keyProps.isNotEmpty()) {
                storeFile = File(keyDir, keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("prism")
        }
        debug {
            // Same key as release so a debug build can be installed over the user's app (and back).
            signingConfig = signingConfigs.getByName("prism")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // MediaPipe memory-maps its models straight from the APK: they must not be compressed
    androidResources {
        noCompress += listOf("tflite", "task")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")

    // CameraX — camera2 backend, lifecycle binding, video (Recorder → MP4), PreviewView (SurfaceView, lowest latency)
    val camerax = "1.7.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device AI (person mask, face mesh, hand gestures) — models bundled in assets/models, no Google services
    implementation("com.google.mediapipe:tasks-vision:1.2.3")
    // its POM lists tasks-core but Gradle did not pull it (NoClassDefFoundError framework.Graph on the tablet)
    implementation("com.google.mediapipe:tasks-core:1.2.3")
    // QR scanning for the PC link (pure Java, tiny)
    implementation("com.google.zxing:core:3.5.4")
}
