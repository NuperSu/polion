import java.util.Properties

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
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "io.polion.android.ReliabilityInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("testSupport/src/main/kotlin"))
    val releaseSigning =
        providers
            .gradleProperty("polion.signing")
            .orElse(System.getProperty("user.home") + "/.config/polion-release/signing.properties")
            .map { file(it) }
            .get()
    if (releaseSigning.isFile) {
        val values = Properties().apply { releaseSigning.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(values.getProperty("storeFile"))
            storePassword = values.getProperty("storePassword")
            keyAlias = values.getProperty("keyAlias")
            keyPassword = values.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // R8 removes Compose's debug overhead; without it the UI runs several times slower than shipped builds.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release key lives outside the repository; without it (CI, other machines) release builds use the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
