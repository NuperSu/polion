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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("testSupport/src/main/kotlin"))
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
