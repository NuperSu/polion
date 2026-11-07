plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.polion.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.polion.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "io.polion.android.ReliabilityInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("testSupport/src/main/kotlin"))
    buildTypes {
        release {
            // R8 removes Compose's debug overhead; without it the UI runs several times slower than shipped builds.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local release builds use the debug key so they install over development builds. Store signing is part of L7.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    lint { warningsAsErrors = true }
}
dependencies {
    androidTestImplementation(libs.kotlinx.serialization.json)
    implementation(project(":core"))
    implementation(project(":sharedUi"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.androidx.work)
}
