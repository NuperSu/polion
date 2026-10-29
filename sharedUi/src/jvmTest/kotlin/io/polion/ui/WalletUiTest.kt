package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.TargetRole
import io.polion.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WalletUiTest {
    @Test
    fun walletDialogValidatesSavesAndRequestsDiscovery() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executorDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                val data = app.snapshot()
                val host =
                    object : PlatformActions {
                        override val dispatcher = executorDispatcher
                        override val dataRevision = MutableStateFlow(0L)
                        override val syncStatus = MutableStateFlow(SyncStatus())
                        override val hasLock = false

                        override fun saveFile(
                            name: String,
                            bytes: ByteArray,
                            onResult: (String) -> Unit,
                        ) = Unit

                        override fun openFile(onResult: (ByteArray?) -> Unit) = Unit

                        override fun restoreBackup(
                            bytes: ByteArray,
                            password: String,
                            onResult: (String) -> Unit,
                        ) = Unit

                        override fun verifyAccountingOrder(onResult: (String) -> Unit) = onResult("Not used")

                        override fun discoverWallet(
                            walletId: TrackingTargetId,
                            onResult: (String) -> Unit,
                        ) = Unit

                        override fun fetchHistoricalPrice(
                            asset: io.polion.core.model.AssetId,
                            quote: io.polion.core.model.AssetId,
                            dayUtcMillis: Long,
                            apiKey: String,
                            onResult: (String) -> Unit,
                        ) = onResult("Daily historical price saved from CoinGecko.")

                        override fun refresh(onResult: (String) -> Unit) = Unit

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
                var discovered: TrackingTargetId? = null
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WalletDialog(data, app, host, {}, {}, { id, done ->
                            discovered = id
                            done("Discovery complete")
                        })
                    }
                    onNodeWithText("Wallet name").performTextInput("Savings")
                    onNodeWithText("Public wallet expression").performTextInput("not-a-wallet")
                    onNodeWithText("New account").assertIsDisplayed()
                    onNodeWithText("Save & discover").assertIsNotEnabled()
                    onNodeWithTag("wallet-source-consent").performScrollTo().performClick()
                    onNodeWithText("Save & discover").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Unsupported descriptor", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Unsupported descriptor", substring = true).assertIsDisplayed()
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.owners
                            .isEmpty(),
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.targets
                            .isEmpty(),
                    )
                    assertFalse(app.sourceConsent())
                    val xpub =
                        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvP" +
                            "EbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
                    onNodeWithText("Public wallet expression").performScrollTo().performTextReplacement(xpub)
                    onNodeWithText("Save & discover").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { discovered != null }
                    val wallet =
                        app
                            .snapshot()
                            .ownership.targets
                            .single { it.id == discovered }
                    assertEquals(TargetRole.WALLET, wallet.role)
                    assertEquals(xpub, wallet.identity.canonicalKey)
                    val saved = app.snapshot()
                    assertEquals(
                        "Savings",
                        saved.ownership.accounts
                            .single()
                            .label,
                    )
                    assertEquals(
                        wallet.accountId,
                        saved.ownership.accounts
                            .single()
                            .id,
                    )
                    assertEquals(
                        "Personal",
                        saved.ownership.owners
                            .single()
                            .label,
                    )
                    assertTrue(saved.sourceConsent)
                    assertNotNull(discovered)
                }
            } finally {
                executorDispatcher.close()
            }
        }
    }
}
