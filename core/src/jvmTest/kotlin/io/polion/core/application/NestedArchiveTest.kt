package io.polion.core.application

import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class NestedArchiveTest {
    @Test
    fun restoringGroupPreservesIndependentChildArchiveAndOriginalTargetIntent() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            val original = app.snapshot().ownership
            app.archiveAccount(AccountId("child"))
            app.archiveAccount(AccountId("group"))
            app.archiveAccount(AccountId("group"))
            val reopened = PortfolioApplication(db) { 1000L }
            reopened.archiveAccount(AccountId("group"), false)
            reopened.archiveAccount(AccountId("group"), false)
            assertArchived(reopened, setOf("child"), setOf("child", "disabled"))
            reopened.archiveAccount(AccountId("child"), false)
            assertEquals(original, reopened.snapshot().ownership)
            assertTrue(db.query("SELECT key FROM application_settings").none { it.single()!!.startsWith("archive") })
        }
    }

    @Test
    fun childIntentCanChangeWhileAncestorKeepsTargetsPaused() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            val original = app.snapshot().ownership
            app.archiveAccount(AccountId("group"))
            app.archiveAccount(AccountId("child"))
            app.archiveAccount(AccountId("group"), false)
            assertArchived(app, setOf("child"), setOf("child", "disabled"))
            app.archiveAccount(AccountId("group"))
            app.archiveAccount(AccountId("child"), false)
            assertArchived(app, setOf("group", "child", "disabled"), setOf("group", "child", "disabled"))
            app.archiveAccount(AccountId("group"), false)
            assertEquals(original, app.snapshot().ownership)
        }
    }

    @Test
    fun archiveFailureRollsBackTagsIntentAndTargetState() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = fixture(db)
            val original = app.snapshot().ownership
            db.execute("CREATE TRIGGER fail_archive BEFORE UPDATE OF enabled ON targets BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            assertFails { app.archiveAccount(AccountId("group")) }
            assertEquals(original, app.snapshot().ownership)
            assertTrue(db.query("SELECT key FROM application_settings").none { it.single()!!.startsWith("archive") })
            db.execute("DROP TRIGGER fail_archive")
            app.archiveAccount(AccountId("group"))
            app.archiveAccount(AccountId("group"), false)
            assertEquals(original, app.snapshot().ownership)
        }
    }

    private fun fixture(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1000L }
        val owner = OwnerId("owner")
        app.saveOwner(Owner(owner, "Owner"))
        for (name in listOf("group", "child", "disabled")) {
            val id = AccountId(name)
            app.saveAccount(
                Account(
                    id,
                    owner,
                    name,
                    if (name == "group") AccountKind.GROUP else AccountKind.ACCOUNT,
                    parentId = if (name == "group") null else AccountId("group"),
                    tags = setOf("custom-$name"),
                ),
            )
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId(name),
                    id,
                    TargetIdentity(NetworkId("test-network"), "address", name),
                    TargetRole.LEAF,
                    name,
                    enabled = name != "disabled",
                ),
            )
        }
        return app
    }

    private fun assertArchived(
        app: PortfolioApplication,
        archived: Set<String>,
        paused: Set<String>,
    ) {
        val catalog = app.snapshot().ownership
        assertEquals(
            archived,
            catalog.accounts
                .filter { "polion:archived" in it.tags }
                .map { it.id.value }
                .toSet(),
        )
        assertEquals(
            paused,
            catalog.targets
                .filterNot { it.enabled }
                .map { it.id.value }
                .toSet(),
        )
        assertTrue(catalog.accounts.all { "custom-${it.id.value}" in it.tags })
    }
}
