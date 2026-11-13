package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.CsvFeeDefault
import io.valroot.core.application.CsvImport
import io.valroot.core.application.CsvImportOptions
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CsvImportFlowTest {
    private val trades =
        "Date,Type,Asset,Quantity,Price,Fee,Notes\r\n" +
            "2026-01-15T10:00:00Z,deposit,USD,5000,,,Opening cash\r\n" +
            "2026-01-15T10:30:00Z,buy,BTC,0.05,42000,1.5,\r\n" +
            "2026-01-16T08:00:00Z,buy,XBT,0.01,43000,,Bought with the XBT ticker\r\n" +
            "2026-01-18T08:00:00Z,bogus,BTC,1,1,,\r\n"

    private fun app(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccount(Account(AccountId("exchange"), OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
        return app
    }

    /** Answers every file request with [reply]: a name and text, or a name without text for an unreadable file. */
    private fun host(
        delegate: ModelWorkspaceRestorationTest.RestorationHost,
        reply: () -> Pair<String, String?>,
    ): PlatformActions =
        object : PlatformActions by delegate {
            override fun openTextFile(
                maxChars: Int,
                onResult: (name: String, text: String?) -> Unit,
            ) {
                val (name, text) = reply()
                onResult(name, text)
            }
        }

    @Test
    fun choosingAFileShowsCountsResolvesAnAssetAndImportsOnce() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = app(db)
                val host = host(delegate) { "trades.csv" to trades }
                var closed = 0
                var changed = 0
                runDesktopComposeUiTest(width = 900, height = 1600) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            CsvImportFlow(app, host, onClose = { closed++ }, onChanged = { changed++ })
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("csv-choose-file").assertIsEnabled() }.isSuccess }
                    onNodeWithText("Import transactions from a CSV file.", substring = true).assertIsDisplayed()
                    onNodeWithTag("csv-choose-file").performClick()

                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("2 new").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("trades.csv").assertIsDisplayed()
                    onNodeWithText("2 can't be imported").assertIsDisplayed()
                    onNodeWithText("Line 4: Choose which asset \"XBT\" is").assertIsDisplayed()
                    onNodeWithText("Line 5: Unknown type \"bogus\"").assertIsDisplayed()
                    onNodeWithText("Times without a time zone are read as", substring = true).assertIsDisplayed()

                    onNodeWithText("What is “XBT”?").assertIsDisplayed()
                    onNodeWithText("Choose an asset", substring = true).performClick()
                    onNodeWithText("BTC –", substring = true).performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("3 new").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("1 can't be imported").assertIsDisplayed()
                    onNodeWithText("What is “XBT”?").assertDoesNotExist()

                    onNodeWithTag("csv-import").assertIsEnabled().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("csv-receipt").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(
                        "Imported 3 transactions. Skipped 0 already imported and 1 that can't be imported.",
                    ).assertIsDisplayed()
                    runOnIdle {
                        assertEquals(3, app.transactions.entries(ActivityFilter()).size)
                        assertEquals(1, changed)
                    }
                    onNodeWithTag("csv-done").performClick()
                    runOnIdle { assertEquals(1, closed) }
                }
            }
        }
    }

    @Test
    fun alreadyImportedFilesAreSkippedAndSaysSo() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = app(db)
                val options =
                    CsvImportOptions(
                        AccountId("exchange"),
                        AssetId("USD"),
                        CsvFeeDefault.CURRENCY,
                        { CsvImport.utcMillis(it) },
                        mapOf("XBT" to BITCOIN_ASSET),
                    )
                app.csv.importFile(app.csv.preview("trades.csv", trades, options))
                val host = host(delegate) { "again.csv" to trades }
                runDesktopComposeUiTest(width = 900, height = 1600) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            CsvImportFlow(app, host, onClose = {})
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("csv-choose-file").assertIsEnabled() }.isSuccess }
                    onNodeWithTag("csv-choose-file").performClick()
                    // The dialog has no asset mapping, so the XBT row stays unresolved and cannot be imported.
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("2 already imported").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("0 new").assertIsDisplayed()
                    onNodeWithText("2 can't be imported").assertIsDisplayed()
                    onNodeWithText("This file was already imported on", substring = true).assertIsDisplayed()
                    onNodeWithTag("csv-import").assertIsNotEnabled()
                }
            }
        }
    }

    @Test
    fun templateIsSavedAndUnreadableFilesAreExplained() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = app(db)
                var reply = "" to (null as String?)
                val host = host(delegate) { reply }
                runDesktopComposeUiTest(width = 900, height = 1600) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            CsvImportFlow(app, host, onClose = {})
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("csv-choose-file").assertIsEnabled() }.isSuccess }
                    onNodeWithTag("csv-save-template").performClick()
                    assertEquals("valroot-import-template.csv", delegate.exportedName)
                    assertTrue(delegate.exported!!.contentEquals(CsvImport.TEMPLATE.encodeToByteArray()))

                    // A cancelled dialog is quiet; a file that cannot be read says why.
                    onNodeWithTag("csv-choose-file").performClick()
                    onNodeWithText("The file could not be read.", substring = true).assertDoesNotExist()
                    reply = "photo.png" to null
                    onNodeWithTag("csv-choose-file").performClick()
                    onNodeWithText("The file could not be read. Use a UTF-8 CSV file up to 5 MB.").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun historyListsImportsAndUndoReversesThem() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = app(db)
                val options =
                    CsvImportOptions(
                        AccountId("exchange"),
                        AssetId("USD"),
                        CsvFeeDefault.CURRENCY,
                        { CsvImport.utcMillis(it) },
                        mapOf("XBT" to BITCOIN_ASSET),
                    )
                val receipt = app.csv.importFile(app.csv.preview("trades.csv", trades, options))
                var changed = 0
                runDesktopComposeUiTest(width = 900, height = 1600) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            CsvImportHistorySection(app, delegate, onChanged = { changed++ })
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("csv-receipt-${receipt.id}").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("3 imported · 0 skipped · 1 not importable").assertIsDisplayed()

                    onNodeWithText("trades.csv").performClick()
                    onNodeWithText("Line 2 · New").assertIsDisplayed()
                    onNodeWithText("Line 5 · Can't import · Unknown type \"bogus\"").assertIsDisplayed()

                    onNodeWithTag("csv-undo-${receipt.id}").performClick()
                    onNodeWithText("Undo this import?").assertIsDisplayed()
                    onNodeWithText(
                        "The 3 transactions it added are reversed. Importing the same file again brings them back.",
                    ).assertIsDisplayed()
                    onNodeWithTag("csv-undo-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("3 transactions undone.").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Undone", substring = true).assertIsDisplayed()
                    runOnIdle {
                        assertTrue(app.transactions.entries(ActivityFilter()).all { it.activity.status == ActivityStatus.REVERSED })
                        assertEquals(1, changed)
                    }
                }
            }
        }
    }

    @Test
    fun emptyHistorySaysNoImportsYet() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = app(db)
                runDesktopComposeUiTest(width = 900, height = 800) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            CsvImportHistorySection(app, delegate, onChanged = {})
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No imports yet.").fetchSemanticsNodes().isNotEmpty() }
                }
            }
        }
    }
}
