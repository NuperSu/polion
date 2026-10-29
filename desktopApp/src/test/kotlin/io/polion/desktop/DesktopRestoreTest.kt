package io.polion.desktop

import io.polion.core.application.PortfolioApplication
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRestoreTest {
    private fun restore(
        host: DesktopHost,
        archive: ByteArray,
        password: String,
    ): String {
        val done = CountDownLatch(1)
        var message = ""
        host.restoreBackup(archive, password) {
            message = it
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return message
    }

    private fun sync(
        host: DesktopHost,
        task: (PortfolioApplication) -> Unit,
    ) {
        val done = CountDownLatch(1)
        var result = ""
        host.runSync(SyncStatus(SyncPhase.SYNCING, "Test"), {
            result = it
            done.countDown()
        }) { application ->
            task(application)
            SyncStatus(SyncPhase.SUCCESS, "Complete")
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals("Complete", result)
    }

    @Test
    fun restoreAfterIdleSyncReopensWorkerOnRestoredDatabase() {
        val directory = Files.createTempDirectory("polion-host-restore-")
        val host = DesktopHost(directory, false)
        try {
            sync(host) { it.saveOwner(Owner(OwnerId("old"), "Old data")) }
            val archive =
                JvmSqliteDatabase(":memory:").use { db ->
                    val source = PortfolioApplication(db) { 1000L }
                    source.saveOwner(Owner(OwnerId("restored"), "Restored data"))
                    source.exportBackup("password".toCharArray())
                }
            assertEquals("Backup restored", restore(host, archive, "password"))
            sync(host) {
                assertEquals(
                    listOf(OwnerId("restored")),
                    it
                        .snapshot()
                        .ownership.owners
                        .map { owner -> owner.id },
                )
                it.saveOwner(Owner(OwnerId("after"), "Written after restore"))
            }
            host.app.value.reload()
            assertEquals(
                setOf(OwnerId("restored"), OwnerId("after")),
                host.app.value
                    .snapshot()
                    .ownership.owners
                    .map { it.id }
                    .toSet(),
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun invalidArchiveLeavesExistingUiAndWorkerDataUsable() {
        val directory = Files.createTempDirectory("polion-host-restore-invalid-")
        val host = DesktopHost(directory, false)
        try {
            sync(host) { it.saveOwner(Owner(OwnerId("old"), "Existing")) }
            assertEquals("Could not restore backup", restore(host, byteArrayOf(1, 2, 3), "password"))
            sync(host) {
                assertEquals(
                    listOf(OwnerId("old")),
                    it
                        .snapshot()
                        .ownership.owners
                        .map { owner -> owner.id },
                )
            }
            assertEquals(
                listOf(OwnerId("old")),
                host.app.value
                    .snapshot()
                    .ownership.owners
                    .map { it.id },
            )
        } finally {
            host.close()
        }
    }
}
