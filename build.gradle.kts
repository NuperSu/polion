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

/** Kotlin files longer than this fail the build; split them by responsibility instead. */
val maxKotlinFileLines = 800

val checkFileSizes by tasks.registering {
    group = "verification"
    description = "Fails when a Kotlin source file exceeds $maxKotlinFileLines lines."
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
                .filter { it.second > maxKotlinFileLines }
                .sortedByDescending { it.second }
        check(tooLong.isEmpty()) {
            "Kotlin files over $maxKotlinFileLines lines:\n" +
                tooLong.joinToString("\n") { (file, lines) -> "  ${file.relativeTo(rootDir)}: $lines" }
        }
    }
}
