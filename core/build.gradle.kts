plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.polion.core"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
