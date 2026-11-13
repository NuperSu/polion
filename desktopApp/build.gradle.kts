plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin { jvmToolchain(25) }
dependencies {
    implementation(project(":core"))
    implementation(project(":sharedUi"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(kotlin("test"))
}
compose.desktop {
    application {
        mainClass = "io.valroot.desktop.MainKt"
        // sqlite-jdbc loads its native library; allow it so newer JDKs do not warn about restricted methods.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            // A self-contained folder with its own Java runtime; `createDistributable` builds it, and the Deb needs dpkg.
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            modules("java.instrument", "java.sql", "jdk.unsupported", "java.net.http")
            packageName = "Valroot"
            packageVersion = "1.0.0"
            description = "Local-first, watch-only crypto portfolio tracker"
            vendor = "NuperSu"
            linux { iconFile.set(project.file("valroot-icon.png")) }
        }
    }
}

// Dependency signatures describe their original archives; the merged distribution has its own contents.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "META-INF/SIG-*")
}
