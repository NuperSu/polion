package io.valroot.ui

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
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.evm.EvmAssets
import io.valroot.core.evm.eip55
import io.valroot.core.evm.evmAddress
import io.valroot.core.evm.evmNetwork
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
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
                app.evm.setAlchemyApiKey("alchemy-test-key-123")
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
                    ) { onAllNodesWithText("Alchemy key: added on this device.").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("evm-chain-1").assertIsDisplayed()
                    assertTrue(onAllNodesWithTag("evm-chain-number").fetchSemanticsNodes().isEmpty())
                    onNodeWithTag("evm-connect-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.evm.targets().isNotEmpty() }
                    val (_, saved) = app.evm.targets().single()
                    assertEquals("0x$address", saved)
                    assertEquals(
                        DEFAULT_EVM_CHAIN_IDS.toSet(),
                        app.evm
                            .chainStates(saved)
                            .map { it.chainId }
                            .toSet(),
                    )
                    assertTrue(app.evm.consent())
                    waitUntil(timeoutMillis = 10_000) { host.refreshRequests > 0 }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun alchemyKeyRowSavesAndRejectsThroughSettings() {
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
                        onAlchemyKey = { key -> withContext(dispatcher) { saveAlchemyKey(app, key) } },
                    )
                runDesktopComposeUiTest(width = 390, height = 1600) {
                    setContent {
                        ValrootTheme(AppearanceTheme.DARK) {
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
                    onNodeWithTag("settings-alchemy-key").performClick()
                    onNodeWithTag("alchemy-key-field").performTextInput("short")
                    onNodeWithTag("alchemy-key-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("alchemy-key-error").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(null, app.evm.alchemyApiKey())
                    onNodeWithTag("alchemy-key-field").performTextReplacement("alchemy-test-key-123")
                    onNodeWithTag("alchemy-key-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("alchemy-key-field").fetchSemanticsNodes().isEmpty() }
                    assertEquals("alchemy-test-key-123", app.evm.alchemyApiKey())
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun chainStatusShowsCoverageNoteUnderItsChainOnly() {
        JvmSqliteDatabase(":memory:").use { db ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = EvmTestHost(executor.asCoroutineDispatcher())
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                val tracked = evmAddress("0x$address")
                val note = "1 sent transaction not listed by Alchemy (for example approvals); their fees are not counted"
                app.evm.addChains(tracked, listOf(1L, 8453L))
                app.evm.recordCoverage(tracked, 8453L, note)
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        EvmAddressDetails(app, host, tracked, revision = 0L, onChanged = {})
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("evm-chain-state-8453").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("evm-coverage-8453").assertIsDisplayed()
                    onNodeWithText(note).assertIsDisplayed()
                    assertTrue(onAllNodesWithTag("evm-coverage-1").fetchSemanticsNodes().isEmpty())
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
                app.accounts.saveOwner(Owner(owner, "Personal"))
                app.accounts.saveAccount(Account(account, owner, "Main", AccountKind.ACCOUNT))
                val token = EvmAssets.token(1, contract)
                app.assets.register(
                    AssetDefinition(token, AssetIdentity(AssetKind.TOKEN, evmNetwork(1), contract)),
                    AssetMetadata("TKN", "Test Token", 18, 0),
                )
                app.transactions.saveManualActivity(
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
