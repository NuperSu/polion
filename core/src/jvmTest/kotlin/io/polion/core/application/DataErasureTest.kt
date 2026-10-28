package io.polion.core.application

import io.polion.core.model.ActivityId
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataErasureTest {
    @Test fun removesPrivateContentsFromActiveDatabaseAndJournals() {
        for (mode in listOf("DELETE", "PERSIST", "TRUNCATE", "WAL")) {
            val directory = Files.createTempDirectory("polion-erasure-")
            val path = directory.resolve("portfolio.sqlite")
            val canary = "PRIVATE_PORTFOLIO_ERASURE_CANARY_"
            try {
                JvmSqliteDatabase(path.toString()).use { db ->
                    db.query("PRAGMA journal_mode = $mode")
                    val app = PortfolioApplication(db) { 15552000000L }
                    app.seedDemo()
                    app.saveOverlay(ActivityOverlay(ActivityId("demo-deposit"), note = canary.repeat(1000)))
                    // Exercise already freed pages as well as currently reachable private records.
                    db.execute("CREATE TABLE erasure_fixture (value TEXT)")
                    db.execute("INSERT INTO erasure_fixture VALUES (?)", listOf(canary.repeat(2000)))
                    db.execute("DELETE FROM erasure_fixture")
                    app.wipe()
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.owners
                            .isEmpty(),
                    )
                    assertTrue(app.snapshot().activities.isEmpty())
                    Files.list(directory).use { files ->
                        files.forEach { file ->
                            assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains(canary), "$mode: ${file.fileName}")
                        }
                    }
                    assertTrue(db.query("PRAGMA integrity_check").single().single() == "ok")
                    app.wipe()
                }
            } finally {
                Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(directory)
            }
        }
    }
}
