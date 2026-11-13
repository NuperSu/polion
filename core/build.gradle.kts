plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

tasks.register<JavaExec>("bitcoinHeapStress") {
    group = "verification"
    description = "Runs two generated Bitcoin refreshes in a separate JVM with a 64 MiB heap."
    dependsOn("jvmTestClasses")
    classpath = tasks.named<Test>("jvmTest").get().classpath
    mainClass.set("io.valroot.core.application.BitcoinHeapStress")
    maxHeapSize = "64m"
    jvmArgs("-XX:+ExitOnOutOfMemoryError")
    args(providers.gradleProperty("bitcoinStressTransactions").orElse("100000").get())
}
kotlin {
    jvmToolchain(25)
    jvm()
    android {
        namespace = "io.valroot.core"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }
    sourceSets.named("jvmTest") { kotlin.srcDir(rootProject.file("testSupport/src/main/kotlin")) }
    // jvmSharedMain: code for both JVM hosts (desktop and Android), such as HTTP transports and platform cryptography.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }
    sourceSets.jvmMain.dependencies {
        implementation(libs.sqlite.jdbc)
        // libsecp256k1 (Bitcoin Core's curve library) for bitcoin-kmp, with natives for Linux, macOS and Windows.
        implementation(libs.secp256k1.kmp.jni.jvm)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.secp256k1.kmp.jni.android)
    }
    sourceSets.commonMain.dependencies {
        implementation(libs.kotlinx.serialization.json)
        // ACINQ's Bitcoin library (used by Phoenix): BIP32 derivation, addresses and descriptor checksums.
        implementation(libs.bitcoin.kmp)
        // Keccak-256 for EIP-55 address checksums.
        implementation(libs.sha3)
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
