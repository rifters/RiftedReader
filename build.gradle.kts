// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 8.5.0+ required for Android 16 KB page size support
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
