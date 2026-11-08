package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetValuation
import io.polion.core.application.PortfolioApplication
import io.polion.core.evm.EvmAssets
import io.polion.core.evm.eip55
import io.polion.core.evm.evmNetwork
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EvmUiTest {
    private val address = "d8da6bf26964af9d7eed9e03e53415d37aa96045"
    private val contract = "0x1111111111111111111111111111111111111111"

    @Test
    fun connectDialogRejectsBadChecksumAndTracksValidAddress() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = EvmTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.setBlockscoutApiKey("blockscout-test-key")
                val good = eip55("0x$address")
                val bad = mistyped(good)
                runDesktopComposeUiTest(width = 800, height = 1400) {
                    setContent {
                        EvmConnectDialog(app, host, app.snapshot(), null, close = {}, onSaved = {}, notify = {})
                    }
                    onNodeWithTag("evm-connect-name").performTextInput("Main wallet")
                    onNodeWithTag("evm-connect-address").performTextInput(bad)
                    onNodeWithTag("evm-connect-address-error", useUnmergedTree = true).assertIsDisplayed()
                    onNodeWithTag("evm-connect-confirm").assertIsNotEnabled()
                    onNodeWithTag("evm-connect-address").performTextReplacement(good)
                    assertTrue(onAllNodesWithTag("evm-connect-address-error", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("Blockscout key: added on this device.").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("evm-connect-consent").performClick()
                    onNodeWithTag("evm-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.evmTargets().isNotEmpty() }
                    val (_, saved) = app.evmTargets().single()
                    assertEquals("0x$address", saved)
                    assertEquals(DEFAULT_EVM_CHAIN_IDS.toSet(), app.evmChainStates(saved).map { it.chainId }.toSet())
                    assertTrue(app.evmConsent())
                    waitUntil(timeoutMillis = 10_000) { host.refreshRequests > 0 }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun blockscoutKeyRowSavesAndRejectsThroughSettings() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val dispatcher = executor.asCoroutineDispatcher()
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                val actions =
                    SettingsActions(
                        onAutomatic = {},
                        onWifi = {},
                        onCurrency = {},
                        onAppearance = {},
                        onAssetPreference = {},
                        onDialog = {},
                        onExportCsv = {},
                        onConnect = {},
                        onLockNow = {},
                        onRemoveLock = {},
                        onSetup = {},
                        onBlockscoutKey = { key -> withContext(dispatcher) { saveBlockscoutKey(app, key) } },
                    )
                runDesktopComposeUiTest(width = 390, height = 1600) {
                    setContent {
                        PolionTheme(AppearanceTheme.DARK) {
                            Column {
                                ProductSettings(
                                    null,
                                    RefreshPreferences(),
                                    false,
                                    AppearanceTheme.DARK,
                                    AssetListPreference(),
                                    true,
                                    {},
                                    actions,
                                    device = SettingsDevice("Pixel", BiometricUnlock.OFF),
                                )
                            }
                        }
                    }
                    onNodeWithTag("settings-keys").performClick()
                    onNodeWithTag("settings-blockscout-key").performClick()
                    onNodeWithTag("blockscout-key-field").performTextInput("short")
                    onNodeWithTag("blockscout-key-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("blockscout-key-error").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(null, app.blockscoutApiKey())
                    onNodeWithTag("blockscout-key-field").performTextReplacement("blockscout-test-key-123")
                    onNodeWithTag("blockscout-key-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("blockscout-key-field").fetchSemanticsNodes().isEmpty() }
                    assertEquals("blockscout-test-key-123", app.blockscoutApiKey())
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun hiddenTokenReviewShowsTokenAndShowMovesItIntoValuations() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = EvmTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                val owner = OwnerId("owner-evm")
                val account = AccountId("account-evm")
                app.saveOwner(Owner(owner, "Personal"))
                app.saveAccount(Account(account, owner, "Main", AccountKind.ACCOUNT))
                val token = EvmAssets.token(1, contract)
                app.registerAsset(
                    AssetDefinition(token, AssetIdentity(AssetKind.TOKEN, evmNetwork(1), contract)),
                    AssetMetadata("TKN", "Test Token", 18, 0),
                )
                app.saveManualActivity(
                    Activity(
                        ActivityId("evm-token-deposit"),
                        1_700_000_000_000L,
                        "evm-token-deposit",
                        ActivityKind.DEPOSIT,
                        ActivityStatus.CONFIRMED,
                        listOf(
                            Movement(
                                EventId("evm-token-deposit"),
                                account,
                                AtomicAmount(token, ExactInteger.parse("5000000000000000000")),
                                MovementRole.TRANSFER,
                            ),
                        ),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                assertTrue(app.snapshot().hiddenAssets.any { it.asset == token })
                val changes = mutableListOf<String>()
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        HiddenAssetsDialog(app, host, app.snapshot(), { changes += it }, close = {})
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Test Token").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("TKN").assertIsDisplayed()
                    onNodeWithTag("hidden-asset-${token.value}").assertIsDisplayed()
                    onNodeWithTag("hidden-Show-${token.value}").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.snapshot().valuations.any { it.asset == token } }
                    assertTrue(app.snapshot().hiddenAssets.none { it.asset == token })
                    assertEquals(listOf("Token shown"), changes)
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun coinTotalsSumChainsAndShowDashForUnpricedParts() {
        val base = EvmAssets.native(8453)
        val mainnet = EvmAssets.native(1)
        val metadata = AssetMetadata("ETH", "Ether", 18, 0)

        fun holding(
            asset: AssetId,
            ether: String,
            value: String?,
        ) = AssetValuation(
            asset,
            AtomicAmount(asset, ExactInteger.parse(ether)),
            metadata,
            PriceStatus.FRESH,
            value?.let { ExactDecimal(ExactInteger.parse(it), 0) },
            null,
        )
        val currency = AssetId("usd")
        val summed =
            coinTotals(
                listOf(holding(mainnet, "1000000000000000000", "2000"), holding(base, "2000000000000000000", "4000")),
                currency,
                emptyList(),
            )
        assertEquals("3 ETH", summed.quantity?.let { formatUnits(it.coefficient, it.scale) }?.let { "$it ETH" })
        assertEquals("6000", summed.value?.let { formatUnits(it.coefficient, it.scale) })
        assertEquals(listOf("Chain 1", "Chain 8453"), summed.chains.map { it.chain })

        val partial = coinTotals(listOf(holding(mainnet, "1000000000000000000", "2000"), holding(base, "1", null)), currency, emptyList())
        assertEquals(null, partial.value)
        assertEquals("—", partial.chains.last().value)
    }

    private fun mistyped(checksummed: String): String {
        val index = checksummed.indices.first { it >= 2 && checksummed[it].isLetter() }
        val flipped = if (checksummed[index].isUpperCase()) checksummed[index].lowercaseChar() else checksummed[index].uppercaseChar()
        return checksummed.substring(0, index) + flipped + checksummed.substring(index + 1)
    }

    /** Records the requests the EVM flows make; provider work is not run. */
    private class EvmTestHost(
        override val dispatcher: CoroutineDispatcher,
    ) : PlatformActions {
        var refreshRequests = 0
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

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
