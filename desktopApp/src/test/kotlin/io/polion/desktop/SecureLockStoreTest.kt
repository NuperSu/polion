package io.polion.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureLockStoreTest {
    @Test
    fun secureStorageAndUnavailableEntryRemainLocked() {
        val dir = Files.createTempDirectory("polion-lock-test")
        val marker = dir.resolve("lock-verifier")
        var secret: String? = null
        val commands = mutableListOf<List<String>>()
        val store =
            SecureLockStore(marker) { args, input ->
                commands.add(args)
                when (args.first()) {
                    "store" -> {
                        secret = input
                        ""
                    }
                    "lookup" -> secret ?: error("unavailable")
                    "clear" -> {
                        secret = null
                        ""
                    }
                    else -> error("unexpected command")
                }
            }
        try {
            store.write("fixture-verifier")
            assertTrue(store.hasLock)
            assertEquals("fixture-verifier", store.read())
            assertFalse(Files.readString(marker).contains("fixture-verifier"))
            assertTrue(commands.all { "fixture-verifier" !in it && dir.toString() !in it })
            secret = null
            assertFails { store.read() }
            assertTrue(store.hasLock)
            store.remove()
            assertFalse(store.hasLock)
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun failedMigrationPreservesLegacyVerifier() {
        val dir = Files.createTempDirectory("polion-lock-test")
        val marker = dir.resolve("lock-verifier")
        try {
            Files.writeString(marker, "legacy-verifier")
            val store = SecureLockStore(marker) { _, _ -> error("unavailable") }
            assertFails { store.migrate(store.read()) }
            assertEquals("legacy-verifier", Files.readString(marker))
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(dir)
        }
    }
}
