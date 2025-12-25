plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

android {
    namespace = "com.rifters.riftedreader"
    compileSdk = 35

    // NDK version for 16 KB page size compatibility (Android 15+ requirement)
    // NDK r28+ includes native 16KB page alignment support
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.rifters.riftedreader"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }

        // Native library configuration for 16 KB page size alignment
        // Required for Android 15+ devices with 16 KB page sizes
        ndk {
            // Support all common ABIs including x86/x86_64 for emulator testing
            // x86_64 is especially important for 16 KB page size emulator testing
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // ExternalNativeBuild arguments for CMake (prepared for future native code)
        // These settings will apply when CMakeLists.txt is added to the project
        externalNativeBuild {
            cmake {
                // Linker flag for 16 KB page alignment
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                cFlags += "-D__ANDROID_16KB_PAGE_SIZE__"
                cppFlags += "-D__ANDROID_16KB_PAGE_SIZE__"
            }
        }

        // Debug/testing flag: enable experimental paginator width sync.
        // Default false to avoid changing pagination behavior.
        // Enable for a build via: ./gradlew :app:assembleDebug -PenableWidthSync=true
        val enableWidthSync = (project.findProperty("enableWidthSync") as String?)
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        buildConfigField("boolean", "MIN_PAGINATOR_ENABLE_WIDTH_SYNC", enableWidthSync.toString())
    }

    buildTypes {
        release {
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
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // Packaging options for native libraries
    packaging {
        // Ensure native libraries are not compressed for proper page alignment
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    
    // WebView support for WebViewAssetLoader (serves local files via virtual https domain)
    implementation("androidx.webkit:webkit:1.12.1")
    
    // Architecture Components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media:media:1.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    // Image Loading
    implementation("io.coil-kt:coil:2.7.0")

    // RecyclerView selection support for bulk actions in the library
    implementation("androidx.recyclerview:recyclerview-selection:1.1.0")
    
    // File format libraries
    // PDF support
    implementation("com.github.mhiew:android-pdf-viewer:3.2.0-beta.1")
    
    // EPUB support (will use custom implementation with JSoup)
    implementation("org.jsoup:jsoup:1.18.3")
    
    // ZIP handling for EPUB and CBZ
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    
    // JSON parsing for Room converters
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
