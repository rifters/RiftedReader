// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 8.5.0+ required for Android 16 KB page size support
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
