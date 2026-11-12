package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.reconcile.ClassificationRule
import io.valroot.core.reconcile.LinkState
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccountsUiTest {
    @Test
    fun accountSourceOpensScanDetails() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val account =
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .id
                app.accounts.saveTarget(
                    io.valroot.core.ownership.TrackingTarget(
                        io.valroot.core.model
                            .TrackingTargetId("source-test"),
                        account,
                        io.valroot.core.ownership.TargetIdentity(
                            io.valroot.core.model
                                .NetworkId("bitcoin-mainnet"),
                            "address",
                            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                        ),
                        io.valroot.core.ownership.TargetRole.LEAF,
                        "Test source",
                    ),
                )
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Accounts").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("View scan details"))
                    onNodeWithText("View scan details").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No saved fetch job").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Source scan details").assertIsDisplayed()
                    onNodeWithText("No saved fetch job").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun compactAccountManagementArchivesAndRestoresWithoutDeletingHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val original = app.snapshot()
                val account = original.ownership.accounts.single()
                val originalRows = db.query("SELECT COUNT(*) FROM ledger_activities").single().single()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Manage accounts"))
                    onNodeWithTag("manage-accounts").assertIsDisplayed().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    val archiveTag = "archive-account-${account.id.value}"
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag(archiveTag))
                    onNodeWithTag(archiveTag).performScrollTo().assertIsEnabled().performClick()
                    onNodeWithText("Archive ${account.label}?").assertIsDisplayed()
                    onNodeWithText("This does not delete the account or its history.", substring = true).assertIsDisplayed()
                    onNodeWithText("Keep account").performClick()
                    assertTrue(
                        "valroot:archived" !in
                            app
                                .snapshot()
                                .ownership.accounts
                                .single()
                                .tags,
                    )
                    onNodeWithTag(archiveTag).performClick()
                    onNodeWithTag("confirm-archive-account").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(
                        "valroot:archived" in
                            app
                                .snapshot()
                                .ownership.accounts
                                .single()
                                .tags,
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.targets
                            .none { it.enabled },
                    )
                    assertEquals(originalRows, db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Account archived; saved history retained").fetchSemanticsNodes().isNotEmpty()
                    }
                    val restoreTag = "restore-account-${account.id.value}"
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag(restoreTag))
                    onNodeWithTag(restoreTag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    onNodeWithText("Account archived; saved history retained").assertIsDisplayed()
                    val restoreBounds = onNodeWithTag(restoreTag).fetchSemanticsNode().boundsInRoot
                    val snackbarBounds = onNodeWithTag("status-message").fetchSemanticsNode().boundsInRoot
                    assertTrue(restoreBounds.bottom <= snackbarBounds.top, "Restore must remain above the active status message")
                    onNodeWithTag(restoreTag).performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        check(
                            onAllNodesWithText("Could not save. Check your input and available storage, then try again.")
                                .fetchSemanticsNodes()
                                .isEmpty(),
                        ) { "Restore was rejected by the save operation" }
                        onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isEmpty()
                    }
                    assertEquals(original.ownership, app.snapshot().ownership)
                    assertEquals(originalRows, db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").assertIsDisplayed().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun phoneCanArchiveAndRestoreAnAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Archive account"))
                    onNodeWithText("Archive account").performScrollTo().performClick()
                    onNodeWithTag("confirm-archive-account").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0, app.snapshot().activityTotal)
                    onNodeWithTag("page-content").performScrollToNode(hasText("Restore account"))
                    onNodeWithText("Restore account").performScrollTo().assertIsEnabled().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Account restored").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(4, app.snapshot().activityTotal)
                }
            }
        }
    }

    @Test
    fun transferReviewRemovesRelationshipsRejectsConflictsAndKeepsSuggestionsUsable() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { 1000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)

                fun activity(
                    id: String,
                    amount: String,
                    status: ActivityStatus = ActivityStatus.CONFIRMED,
                ) = Activity(
                    ActivityId(id),
                    100,
                    id,
                    ActivityKind.TRANSFER,
                    status,
                    listOf(
                        Movement(
                            EventId("$id-event"),
                            AccountId(id),
                            AtomicAmount(AssetId("bitcoin:btc"), ExactInteger.parse(amount)),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                )
                val records =
                    listOf(
                        activity("accepted-out", "-10"),
                        activity("accepted-in", "10"),
                        activity("conflict-out", "-20"),
                        activity("conflict-in", "20", ActivityStatus.REVERSED),
                        activity("candidate-out", "-30"),
                        activity("candidate-in", "30"),
                    )
                val repository = ActivityRepository(db)
                records.forEach { repository.save(it) }
                app.transactions.setDecision(ActivityId("accepted-out"), ActivityId("accepted-in"), LinkState.ACCEPTED)
                app.transactions.setDecision(ActivityId("conflict-out"), ActivityId("conflict-in"), LinkState.ACCEPTED)
                val original = repository.current()
                val data = app.snapshot()
                var closed = false
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        WorkflowDialog("reconcile", data, app, host, { closed = true }, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Out: ", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    // Rows describe each side by date, account and amount rather than internal IDs.
                    onNodeWithText("Out: ", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("In: ", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Unresolved: activity inactive").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("remove-transfer-accepted-out-accepted-in").performScrollTo().performClick()
                    onNodeWithText("No accepted transfer relationships.").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("reject-transfer-conflict-conflict-in-conflict-out").performScrollTo().performClick()
                    onNodeWithText("No transfer link conflicts.").performScrollTo().assertIsDisplayed()
                    runOnIdle {
                        assertTrue(app.accounting.transferLinks().isEmpty())
                        assertTrue(app.accounting.transferLinkConflicts().isEmpty())
                        assertEquals(1, app.accounting.reconciliationCandidates(86_400_000).size)
                        assertEquals(original, repository.current())
                    }
                    onNodeWithTag("accept-transfer-candidate-in-candidate-out").performScrollTo().performClick()
                    runOnIdle {
                        assertTrue(closed)
                        assertEquals(
                            ActivityId("candidate-out"),
                            app.accounting
                                .transferLinks()
                                .single()
                                .outgoingActivity,
                        )
                        assertEquals(original, repository.current())
                        assertTrue(app.accounting.reconciliationCandidates(86_400_000).isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun ruleManagerEditsAndTogglesWithoutBroadeningFilters() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                val original =
                    ClassificationRule(
                        "filtered-rule",
                        37,
                        ActivityKind.DEPOSIT,
                        ActivityKind.PAYMENT,
                        AssetId("bitcoin:btc"),
                        SourceRecordId("filtered-source"),
                        enabled = false,
                    )
                val anyKind = original.copy(id = "any-kind-rule", matchKind = null, priority = 12)
                app.transactions.saveRule(original)
                app.transactions.saveRule(anyKind)
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        WorkflowDialog("rule", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(original.id).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("edit-rule-${original.id}").performScrollTo().performClick()
                    onNodeWithText("Match asset ID (blank for any)").performScrollTo().assertIsDisplayed()
                    onNodeWithText("deposit").performScrollTo().performClick()
                    onNodeWithText("reward").performClick()
                    onNodeWithText("Save rule").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD, enabled = true),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("edit-rule-${anyKind.id}").performScrollTo().performClick()
                    onNodeWithText("Any classification").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Save rule").performScrollTo().performClick()
                    runOnIdle { assertEquals(anyKind, app.transactions.rules().first { it.id == anyKind.id }) }
                }
            }
        }
    }
}
