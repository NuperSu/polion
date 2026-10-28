plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}
kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.polion.ui"
        compileSdk = 37
        minSdk = 26
    }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation(compose.desktop.uiTestJUnit4)
        implementation(compose.desktop.currentOs)
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":core"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
    }
}
