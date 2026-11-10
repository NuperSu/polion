package io.valroot.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataDirectoryTest {
    @Test fun theFormerFolderMovesOnceAndIsNeverMerged() {
        val share = Files.createTempDirectory("valroot-share-")
        val legacy = Files.createDirectory(share.resolve("polion"))
        Files.writeString(legacy.resolve("portfolio.sqlite"), "data")
        val directory = dataDirectory(share)
        assertEquals(share.resolve("valroot"), directory)
        assertEquals("data", Files.readString(directory.resolve("portfolio.sqlite")))
        assertFalse(Files.exists(legacy))
        // A former folder that appears again is left alone once Valroot's exists.
        Files.createDirectory(legacy)
        dataDirectory(share)
        assertTrue(Files.exists(legacy))
        assertFalse(Files.exists(directory.resolve("polion")))
    }
}
