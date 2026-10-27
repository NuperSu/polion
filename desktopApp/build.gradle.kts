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
}
compose.desktop {
    application { mainClass = "io.polion.desktop.MainKt" }
}
