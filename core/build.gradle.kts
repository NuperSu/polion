plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

tasks.register<JavaExec>("bitcoinHeapStress") {
    group = "verification"
    description = "Runs two generated Bitcoin refreshes in a separate JVM with a 64 MiB heap."
    dependsOn("jvmTestClasses")
    classpath = tasks.named<Test>("jvmTest").get().classpath
    mainClass.set("io.polion.core.application.BitcoinHeapStress")
    maxHeapSize = "64m"
    jvmArgs("-XX:+ExitOnOutOfMemoryError")
    args(providers.gradleProperty("bitcoinStressTransactions").orElse("100000").get())
}
kotlin {
    jvmToolchain(25)
    jvm()
    android {
        namespace = "io.polion.core"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }
    sourceSets.named("jvmTest") { kotlin.srcDir(rootProject.file("testSupport/src/main/kotlin")) }
    sourceSets.jvmMain.dependencies {
        implementation("org.xerial:sqlite-jdbc:3.49.1.0")
        // libsecp256k1 (Bitcoin Core's curve library) for bitcoin-kmp, with natives for Linux, macOS and Windows.
        implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
    }
    sourceSets.androidMain.dependencies {
        implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0")
    }
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        // ACINQ's Bitcoin library (used by Phoenix): BIP32 derivation, addresses and descriptor checksums.
        implementation("fr.acinq.bitcoin:bitcoin-kmp:0.32.0")
        // Keccak-256 for EIP-55 address checksums.
        implementation("org.kotlincrypto.hash:sha3:0.8.0")
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
