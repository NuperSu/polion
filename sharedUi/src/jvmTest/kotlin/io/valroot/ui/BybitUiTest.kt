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
                    assertTrue(app.exchangeTargets(ExchangeKind.BYBIT).isEmpty())
                    // A read-only key adds the account it belongs to, keeps the key on the device and starts a refresh.
                    host.info = ExchangeKeyInfo("1001", readOnly = true, expiresAtMillis = null)
                    onNodeWithTag("bybit-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.exchangeTargets(ExchangeKind.BYBIT).isNotEmpty() }
                    val (account, uid) = app.exchangeTargets(ExchangeKind.BYBIT).single()
                    assertEquals("1001", uid)
                    assertEquals("KEYKEYKEYKEY" to "SECRETSECRETSECRET", app.exchangeKey(ExchangeKind.BYBIT, account))
                    waitUntil(timeoutMillis = 10_000) { host.refreshRequests > 0 }
                    assertEquals(listOf("KEYKEYKEYKEY" to "SECRETSECRETSECRET"), host.checked.distinct())
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
