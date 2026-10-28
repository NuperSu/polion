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
    sourceSets.jvmMain.dependencies {
        implementation("org.xerial:sqlite-jdbc:3.49.1.0")
        implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    }
    sourceSets.androidMain.dependencies {
        implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    }
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
