/*
 * App module. Uses Jetpack Compose for UI.
 *
 * No separate org.jetbrains.kotlin.android plugin is applied: AGP 9's
 * built-in Kotlin support handles Kotlin sources automatically, and
 * errors out if that plugin is applied alongside it. The Compose
 * compiler, however, is a distinct Kotlin compiler plugin
 * (org.jetbrains.kotlin.plugin.compose) and must still be applied
 * explicitly, pinned to a Kotlin version compatible with AGP 9's
 * bundled Kotlin compiler.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.example.test"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.example.test"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.sceneview)
    debugImplementation(libs.androidx.ui.tooling)
}
