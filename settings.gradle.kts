/*
 * Root settings file. Updated to add the repositories AGP needs
 * (Google's Maven repo for the Android Gradle plugin + AndroidX artifacts).
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "test"
include("app")
