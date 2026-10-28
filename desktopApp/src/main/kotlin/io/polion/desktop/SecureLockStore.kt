package io.polion.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun interface SecretCommand {
    fun run(
        arguments: List<String>,
        input: String?,
    ): String
}

internal class SecretToolCommand : SecretCommand {
    override fun run(
        arguments: List<String>,
        input: String?,
    ): String {
        val process =
            ProcessBuilder(listOf("secret-tool") + arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Secret Service timed out" }
            check(process.exitValue() == 0) { "Secret Service unavailable" }
            return process.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trimEnd('\n', '\r')
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** The marker keeps the screen locked even when the Secret Service entry is unavailable. */
internal class SecureLockStore(
    private val marker: Path,
    private val command: SecretCommand = SecretToolCommand(),
) {
    private val attributes = listOf("application", "polion", "profile", profileId(marker.parent))
    val hasLock: Boolean get() = Files.exists(marker)

    fun read(): String {
        val value = Files.readString(marker)
        return if (value == MARKER) {
            command.run(listOf("lookup") + attributes, null).also { check(it.isNotEmpty()) }
        } else {
            value
        }
    }

    fun write(verifier: String) {
        command.run(listOf("store", "--label=Polion privacy lock") + attributes, verifier)
        val temporary = Files.createTempFile(marker.parent, "lock-", ".tmp")
        try {
            Files.setPosixFilePermissions(temporary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            Files.writeString(temporary, MARKER)
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun migrate(verifier: String) {
        if (Files.readString(marker) != MARKER) write(verifier)
    }

    fun remove() {
        if (Files.readString(marker) == MARKER) command.run(listOf("clear") + attributes, null)
        Files.delete(marker)
    }

    private fun profileId(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                path
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MARKER = "polion-secret-service-v1"
    }
}
