package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeKeyInfo
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BybitUiTest {
    @Test
    fun connectChecksTheKeyAndTracksTheAccountItNames() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = BybitTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                runDesktopComposeUiTest(width = 800, height = 1400) {
                    setContent {
                        ExchangeConnectDialog(
                            ExchangeKind.BYBIT,
                            app,
                            host,
                            app.snapshot(),
                            null,
                            close = {},
                            onSaved = {},
                            notify = {},
                        )
                    }
                    onNodeWithTag("bybit-connect-confirm").assertIsNotEnabled()
                    onNodeWithTag("bybit-key").performTextInput("KEYKEYKEYKEY")
                    onNodeWithTag("bybit-secret").performTextInput("SECRETSECRETSECRET")
                    // A key that can trade is refused, and nothing is saved.
                    host.info = ExchangeKeyInfo("1001", readOnly = false, expiresAtMillis = null)
                    onNodeWithTag("bybit-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("bybit-connect-error").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("bybit-connect-error").assertIsDisplayed()
                    assertTrue(app.exchanges.targets(ExchangeKind.BYBIT).isEmpty())
                    // A read-only key adds the account it belongs to, keeps the key on the device and starts a refresh.
                    host.info = ExchangeKeyInfo("1001", readOnly = true, expiresAtMillis = null)
                    onNodeWithTag("bybit-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.exchanges.targets(ExchangeKind.BYBIT).isNotEmpty() }
                    val (account, uid) = app.exchanges.targets(ExchangeKind.BYBIT).single()
                    assertEquals("1001", uid)
                    assertEquals("KEYKEYKEYKEY" to "SECRETSECRETSECRET", app.exchanges.key(ExchangeKind.BYBIT, account))
                    waitUntil(timeoutMillis = 10_000) { host.refreshRequests > 0 }
                    assertEquals(listOf("KEYKEYKEYKEY" to "SECRETSECRETSECRET"), host.checked.distinct())
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun mexcConnectTracksTheAccountItsKeyReads() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = BybitTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                runDesktopComposeUiTest(width = 800, height = 1400) {
                    setContent {
                        ExchangeConnectDialog(
                            ExchangeKind.MEXC,
                            app,
                            host,
                            app.snapshot(),
                            null,
                            close = {},
                            onSaved = {},
                            notify = {},
                        )
                    }
                    onNodeWithTag("mexc-key").performTextInput("mx0vglKEYKEYKEY")
                    onNodeWithTag("mexc-secret").performTextInput("SECRETSECRETSECRET")
                    host.info = ExchangeKeyInfo("12345678", readOnly = true, expiresAtMillis = null)
                    onNodeWithTag("mexc-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.exchanges.targets(ExchangeKind.MEXC).isNotEmpty() }
                    val (account, uid) = app.exchanges.targets(ExchangeKind.MEXC).single()
                    assertEquals("12345678", uid)
                    assertEquals("mx0vglKEYKEYKEY" to "SECRETSECRETSECRET", app.exchanges.key(ExchangeKind.MEXC, account))
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun binanceConnectUsesTheSameChecksAndShowsTradingPairs() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = BybitTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                runDesktopComposeUiTest(width = 800, height = 1400) {
                    setContent {
                        ExchangeConnectDialog(
                            ExchangeKind.BINANCE,
                            app,
                            host,
                            app.snapshot(),
                            null,
                            close = {},
                            onSaved = {},
                            notify = {},
                        )
                    }
                    onNodeWithTag("binance-key").performTextInput("KEYKEYKEYKEY")
                    onNodeWithTag("binance-secret").performTextInput("SECRETSECRETSECRET")
                    host.info = ExchangeKeyInfo("777", readOnly = false, expiresAtMillis = null)
                    onNodeWithTag("binance-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("binance-connect-error").fetchSemanticsNodes().isNotEmpty() }
                    assertTrue(app.exchanges.targets(ExchangeKind.BINANCE).isEmpty())
                    host.info = ExchangeKeyInfo("777", readOnly = true, expiresAtMillis = null)
                    onNodeWithTag("binance-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.exchanges.targets(ExchangeKind.BINANCE).isNotEmpty() }
                    val (account, _) = app.exchanges.targets(ExchangeKind.BINANCE).single()
                    assertEquals("KEYKEYKEYKEY" to "SECRETSECRETSECRET", app.exchanges.key(ExchangeKind.BINANCE, account))
                    assertTrue(app.exchanges.targets(ExchangeKind.BYBIT).isEmpty())
                }
                runDesktopComposeUiTest(width = 800, height = 1400) {
                    val (account, _) = app.exchanges.targets(ExchangeKind.BINANCE).single()
                    setContent { ExchangeAccountDetails(ExchangeKind.BINANCE, app, host, account, 0, onChanged = {}) }
                    onNodeWithTag("binance-add-pair").performClick()
                    onNodeWithTag("pair-base").performTextInput("sol")
                    onNodeWithTag("pair-quote").performTextInput("usdt")
                    onNodeWithTag("pair-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.exchanges.pairs(account).containsKey("SOLUSDT") }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    private class BybitTestHost(
        override val dispatcher: CoroutineDispatcher,
    ) : PlatformActions {
        var refreshRequests = 0
        var info: ExchangeKeyInfo? = null
        val checked = mutableListOf<Pair<String, String>>()
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

        override fun exchangeKeyInfo(
            kind: ExchangeKind,
            key: String,
            secret: String,
        ): ExchangeKeyInfo {
            checked += key to secret
            return info ?: throw IllegalStateException("Bybit did not accept this API key.")
        }

        override fun saveFile(
            name: String,
            bytes: ByteArray,
            onResult: (String) -> Unit,
        ) = Unit

        override fun saveBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun restoreBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun verifyAccountingOrder(onResult: (String) -> Unit) = onResult("Not used")

        override fun discoverWallet(
            walletId: TrackingTargetId,
            onResult: (String) -> Unit,
        ) = Unit

        override fun fetchHistoricalPrice(
            asset: AssetId,
            quote: AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun refresh(onResult: (String) -> Unit) {
            refreshRequests++
            onResult("Refresh requested")
        }

        override fun recoverBitcoinHistory(onResult: (String) -> Unit) = Unit

        override fun setBackgroundRefresh(enabled: Boolean) = Unit

        override fun backgroundRefreshEnabled() = false

        override fun lock() = Unit

        override fun unlock(
            password: String,
            onResult: (UnlockResult) -> Unit,
        ) = Unit

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun removeLockPassword(onResult: (String) -> Unit) = Unit
    }
}
