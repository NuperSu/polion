plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(project(":sharedUi"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(kotlin("test"))
}
compose.desktop {
    application { mainClass = "io.polion.desktop.MainKt" }
}

// Dependency signatures describe their original archives; the merged distribution has its own contents.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "META-INF/SIG-*")
}
