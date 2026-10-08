plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

android {
    namespace = "com.neo.testcompose"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.neo.speaktouch.testcompose"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.neo.testcompose.HierarchyCapture"
    }

    buildFeatures { compose = true }
}

dependencies {
    // Pin this reproduction track; existing app/test dependency graphs are unchanged.
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")
}
