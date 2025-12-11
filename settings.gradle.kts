pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        // Your existing Android + Kotlin plugin versions should remain:
        id("com.android.application") version "8.5.0" // or whatever you already use
        id("org.jetbrains.kotlin.android") version "1.9.0" // example, do NOT change unless needed

        // ADD THESE TWO:
        id("com.google.gms.google-services") version "4.4.2"
        id("com.google.firebase.crashlytics") version "3.0.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HotLapsDynamic"
include(":app")
