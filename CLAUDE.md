# HotLapsDynamic (Apex Dynamics) — Claude Context

## Project Overview
Real-time G-force telemetry and track performance analysis app for Android.
Records accelerometer + GPS data during track days, detects corner apexes, and exports telemetry CSV files.

## Build & Run
- Open in Android Studio, run on device or emulator
- Build: `./gradlew assembleDebug`
- Clean build: `./gradlew clean assembleDebug`
- Min SDK: 26 (Android 8.0), Target: 35, Compile: 36
- Java 17, Kotlin, Jetpack Compose

## Package Structure
```
app/src/main/java/com/hotlaps/dynamic/
  data/         — Storage singletons (EventStorage, TrackStorage, FileHelper, SettingsRepo, CalibRepo)
  model/        — Data classes (Track, Corner, Event, EventSample, CornerVisit)
  ui/           — Jetpack Compose screens
  ui/help/      — Help/documentation screens
  util/         — GForceSmoother, MovingAverage2D, GeoUtils, FeedbackUtils
  viewmodel/    — DriveViewModel, TrackSelectionViewModel
  Color.kt, Theme.kt, Type.kt — Design system
app/src/main/assets/ — Built-in track JSON files
```

## Key Architecture Notes
- **MVVM** with Jetpack Compose + StateFlow/MutableStateFlow
- **DriveViewModel** is the core recording state machine — handles sensor data, GPS, corner detection, apex marking
- **EventStorage** is the source of truth for telemetry — CSV-based, append-only during recording
- **TrackStorage** uses JSON files in app-private storage; built-in tracks seeded from assets on first run
- **SettingsRepo / CalibRepo** use DataStore (reactive, no restart needed)
- Navigation via `NavHost` with URL-like string routes (no Activities per screen)
- Singleton repositories (`object`) for storage classes

## Data Storage Locations
- App-private: `/Android/data/com.hotlaps.dynamic/files/tracks/` and `.../events/`
- Public export: `/Download/ApexDynamics/events/` and `.../tracks/`

## Core Algorithms
- **Apex detection**: collects GPS distance samples inside corner trigger radius, picks 4 closest → apex time
- **G-force smoothing**: EMA (time-adaptive alpha) → Moving Average chain; 4 presets (Off/Low/Medium/Heavy)
- **Corner detection**: Haversine distance to each corner apex coordinate, state machine per corner (outside→inside→outside)
- **GPS interpolation**: Catmull-ROM spline between GPS fixes (current branch: GPSInterpolation)

## Current Branch: GPSInterpolation
Active work is on GPS interpolation between fixes to improve smoothness of GPS-derived data.

## Dependencies
- Jetpack Compose + Material3
- Navigation Compose 2.8.3
- Coroutines 1.9.0
- DataStore Preferences 1.1.1
- Gson 2.10.1
- Firebase Crashlytics

## Permissions
- `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` — requested at runtime in MainActivity
