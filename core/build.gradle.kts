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
    jvmToolchain(21)
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
