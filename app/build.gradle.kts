
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hotlaps.dynamic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hotlaps.dynamic"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-Beta1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Smaller, faster builds
            buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
            buildConfigField("String", "BUILD_TIME", "\"${buildTime()}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Optional: faster incremental builds
            // applicationIdSuffix = ".debug"
            buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
            buildConfigField("String", "BUILD_TIME", "\"${buildTime()}\"")
        }
    }

    compileOptions {
        // Prefer Java 17 for modern toolchains
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Optional: enable inline classes & other opts later if desired
        // freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true   // <-- ensure BuildConfig is generated
    }

    // Compose compiler version is managed by the Kotlin Compose plugin + BOM
    packaging {
        // Avoid common META-INF collisions if they pop up later
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*}"
    }
}

dependencies {
    // --- Core / Compose ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM keeps UI libs aligned
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("com.google.android.material:material:1.12.0")
    // Jetpack Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.compose.ui:ui-text:1.7.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")



    // Lifecycle (Compose-aware)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Coroutines (for sensor sampling / timers)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore (prefs for ggMaxAbsG, trailSeconds)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Test / Tooling ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}


// Helpers
fun gitSha(): String = try {
    val p = Runtime.getRuntime().exec("git rev-parse --short=7 HEAD")
    p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
} catch (_: Exception) { "nogit" }

fun buildTime(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())