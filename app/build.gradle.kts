plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.navaneeth.filer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.navaneeth.filer"
        minSdk = 31
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 4
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release key lives outside the repo: its path and passwords come from ~/.gradle/gradle.properties
    // (FILER_RELEASE_*). Without them the release APK is built unsigned.
    val releaseStore = (project.findProperty("FILER_RELEASE_STORE_FILE") as String?)?.let(::file)
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = project.findProperty("FILER_RELEASE_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("FILER_RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("FILER_RELEASE_KEY_PASSWORD") as String?
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            // R8: shrinks the app's code (≈61 MB of dex unshrunk). Keep rules in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // Release-like build for measuring performance locally: not debuggable, signed with the debug key.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            // `-PdebuggableBenchmark=true` makes an otherwise identical build inspectable (run-as, logs).
            isDebuggable = project.findProperty("debuggableBenchmark") == "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xskip-metadata-version-check"
        )
    }

    buildFeatures {
        compose = true
    }

    // One APK per processor type: a phone only needs its own native libraries (each set is
    // 40–90 MB). arm64-v8a covers nearly every current phone; 32-bit ARM for older ones; x86_64 for
    // emulators and Chromebooks.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// files.db is migrated, never rebuilt: its schemas are kept so each migration can be checked.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM & UI
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose with Type-Safe Routing
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Storage Access Framework & Media
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // PDF text extraction (PdfBox-Android)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Local Persistence: Room (SQLite)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background Processing: WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Resumable Model Downloader (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MediaPipe Retrieval / Embedder & LiteRT-LM Runtime
    // Telemetry fully stripped: tasks-core patched to use TasksStatsDummyLogger,
    // and all datatransport dependencies excluded so nothing leaves the phone.
    implementation("com.google.mediapipe:tasks-retrieval:1.1.0") {
        exclude(group = "com.google.mediapipe", module = "tasks-core")
        exclude(group = "com.google.android.datatransport")
    }
    implementation(files("libs/tasks-core-patched.aar"))
    implementation("com.google.protobuf:protobuf-javalite:4.26.1")
    implementation("com.google.guava:guava:27.0.1-android")
    implementation("com.google.flogger:flogger:0.6")
    implementation("com.google.flogger:flogger-system-backend:0.6")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")

    // AI plugins: OCR (PP-OCRv6) and speech (Whisper) models run on ONNX Runtime, downloaded on request
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    testImplementation("junit:junit:4.13.2")
}
