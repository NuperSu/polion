plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin { jvmToolchain(25) }
dependencies {
    implementation(project(":sharedUi"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}
compose.desktop {
    application { mainClass = "io.polion.model.MainKt" }
}
