plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.ktlint)
}
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

val checkFileSizes by tasks.registering {
    group = "verification"
    description = "Fails when a Kotlin source file exceeds 800 lines; split such files by responsibility."
    val limit = 800
    val root = rootDir
    val sources =
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**", "UI_TO_COPY/**", ".claude/**")
        }
    inputs.files(sources)
    doLast {
        val tooLong =
            sources.files
                .map { it to it.readLines().size }
                .filter { it.second > limit }
                .sortedByDescending { it.second }
        check(tooLong.isEmpty()) {
            "Kotlin files over $limit lines:\n" + tooLong.joinToString("\n") { (file, lines) -> "  ${file.relativeTo(root)}: $lines" }
        }
    }
}

subprojects {
    tasks.matching { it.name == "ktlintCheck" }.configureEach { dependsOn(rootProject.tasks.named("checkFileSizes")) }
}
