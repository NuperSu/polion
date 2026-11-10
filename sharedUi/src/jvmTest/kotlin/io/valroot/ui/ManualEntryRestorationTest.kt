package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactInteger
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ManualEntryRestorationTest {
    @Test
    fun recreationPreservesAssetAccountClassificationAndAmountInRecordedActivity() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.seedDemo()
            val initial = app.snapshot()
            val secondAccount = AccountId("second-wallet")
            app.saveAccount(
                Account(
                    secondAccount,
                    initial.ownership.owners
                        .first()
                        .id,
                    "Second wallet",
                    AccountKind.WALLET,
                ),
            )
            val usd = AssetId("USD")
            val data =
                app.snapshot().let {
                    it.copy(
                        valuations =
                            it.valuations +
                                AssetValuation(
                                    usd,
                                    AtomicAmount(usd, ExactInteger.ZERO),
                                    AssetMetadata("USD", "US Dollar", 2, 0),
                                    PriceStatus.MISSING,
                                    null,
                                    null,
                                ),
                    )
                }
            val visible = mutableStateOf(true)
            var saved = emptyMap<String, List<Any?>>()
            var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
            val host = manualHost()
            runDesktopComposeUiTest(width = 600, height = 900) {
                setContent {
                    if (visible.value) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            WorkflowDialog("activity", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                        }
                    }
                }
                onNodeWithText("Example wallet").performClick()
                onNodeWithText("Second wallet").performClick()
                onNodeWithText("deposit").performClick()
                onNodeWithText("reward").performClick()
                onNodeWithText("Bitcoin").performClick()
                onNodeWithText("US Dollar").performClick()
                onNodeWithText("USD quantity (positive or negative)").performTextInput("12.34")
                runOnIdle {
                    saved = registry.performSave()
                    visible.value = false
                }
                onNodeWithText("Record locally").assertDoesNotExist()
                runOnIdle {
                    registry = SaveableStateRegistry(saved, canBeSaved = { true })
                    visible.value = true
                }
                onNodeWithText("Second wallet").assertIsDisplayed()
                onNodeWithText("reward").assertIsDisplayed()
                onNodeWithText("US Dollar").assertIsDisplayed()
                onNodeWithText("12.34").assertIsDisplayed()
                onNodeWithText("Record locally").performScrollTo().performClick()
                runOnIdle {
                    val recorded =
                        app
                            .snapshot(ActivityFilter(asset = usd))
                            .activities
                            .single()
                            .activity
                    assertEquals(ActivityKind.REWARD, recorded.kind)
                    assertEquals(secondAccount, recorded.movements.single().accountId)
                    assertEquals(
                        usd,
                        recorded.movements
                            .single()
                            .amount.assetId,
                    )
                    assertEquals(
                        ExactInteger.parse("1234"),
                        recorded.movements
                            .single()
                            .amount.atomicUnits,
                    )
                }
            }
        }
    }

    @Test
    fun manualFormRejectsWrongSignsAndInvalidFeesWithoutWritingActivity() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.seedDemo()
            val data = app.snapshot()
            val initialCount = data.activityTotal
            val host = manualHost()
            runDesktopComposeUiTest(width = 600, height = 900) {
                setContent { WorkflowDialog("activity", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> }) }
                onNodeWithText("deposit").performClick()
                onNodeWithText("trade").assertDoesNotExist()
                onNodeWithText("transfer").assertDoesNotExist()
                onNodeWithText("payment").performClick()
                onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextInput("0.1")
                onNodeWithText("Record locally").performScrollTo().performClick()
                onNodeWithText("Use a positive quantity for deposits/rewards or a negative quantity for payments.")
                    .performScrollTo()
                    .assertIsDisplayed()
                runOnIdle { assertEquals(initialCount, app.snapshot().activityTotal) }
                onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextReplacement("-0.1")
                listOf("0", "-0.0001", "not a fee", "0.000000001").forEach { invalidFee ->
                    onNodeWithText("Fee in BTC (optional, positive)").performScrollTo().performTextReplacement(invalidFee)
                    onNodeWithText("Record locally").performScrollTo().performClick()
                    onNodeWithText("Enter a positive fee in BTC or leave the fee empty.").performScrollTo().assertIsDisplayed()
                    runOnIdle { assertEquals(initialCount, app.snapshot().activityTotal) }
                }
                onNodeWithText("Fee in BTC (optional, positive)").performScrollTo().performTextReplacement("")
                onNodeWithText("Date & time (UTC, ISO 8601; empty = now)").performScrollTo().performTextInput("not a date")
                onNodeWithText("Record locally").performScrollTo().performClick()
                onNodeWithText("Enter a UTC date and time such as 2026-11-01T12:00:00Z.").performScrollTo().assertIsDisplayed()
                runOnIdle { assertEquals(initialCount, app.snapshot().activityTotal) }
                onNodeWithText("payment").performScrollTo().performClick()
                onNodeWithText("reward").performClick()
                onNodeWithText("Record locally").performScrollTo().performClick()
                onNodeWithText("Use a positive quantity for deposits/rewards or a negative quantity for payments.")
                    .performScrollTo()
                    .assertIsDisplayed()
                runOnIdle { assertEquals(initialCount, app.snapshot().activityTotal) }
                onNodeWithText("reward").performScrollTo().performClick()
                onNodeWithText("deposit").performClick()
                onNodeWithText("Record locally").performScrollTo().performClick()
                onNodeWithText("Use a positive quantity for deposits/rewards or a negative quantity for payments.")
                    .performScrollTo()
                    .assertIsDisplayed()
                runOnIdle { assertEquals(initialCount, app.snapshot().activityTotal) }
            }
        }
    }

    @Test
    fun manualPaymentRecordsExactPrincipalFeeAndEnteredTimestamp() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.seedDemo()
            val data = app.snapshot()
            val host = manualHost()
            val enteredTime = "2026-11-01T12:34:56.789Z"
            var closed = false
            runDesktopComposeUiTest(width = 600, height = 900) {
                setContent {
                    WorkflowDialog(
                        "activity",
                        data,
                        app,
                        host,
                        { closed = true },
                        { _, operation -> operation() },
                        {},
                        { _, _ -> },
                    )
                }
                onNodeWithText("deposit").performClick()
                onNodeWithText("payment").performClick()
                onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextInput("-0.125")
                onNodeWithText("Fee in BTC (optional, positive)").performScrollTo().performTextInput("0.00001001")
                onNodeWithText("Date & time (UTC, ISO 8601; empty = now)").performScrollTo().performTextInput(enteredTime)
                onNodeWithText("Label (optional)").performScrollTo().performTextInput("Test payment with fee")
                onNodeWithText("Record locally").performScrollTo().performClick()
                runOnIdle {
                    assertEquals(true, closed)
                    val recorded =
                        app
                            .snapshot(ActivityFilter(search = "Test payment with fee"))
                            .activities
                            .single()
                            .activity
                    assertEquals(ActivityKind.PAYMENT, recorded.kind)
                    assertEquals(Instant.parse(enteredTime).toEpochMilliseconds(), recorded.occurredAtEpochMillis)
                    assertEquals(2, recorded.movements.size)
                    assertEquals(
                        setOf(
                            data.ownership.accounts
                                .single()
                                .id,
                        ),
                        recorded.movements.map { it.accountId }.toSet(),
                    )
                    assertEquals(setOf(AssetId("bitcoin:btc")), recorded.movements.map { it.amount.assetId }.toSet())
                    val principal = recorded.movements.single { it.role == MovementRole.TRANSFER }
                    val fee = recorded.movements.single { it.role == MovementRole.FEE }
                    assertEquals(ExactInteger.parse("-12500000"), principal.amount.atomicUnits)
                    assertEquals(ExactInteger.parse("-1001"), fee.amount.atomicUnits)
                }
            }
        }
    }

    private fun manualHost(): PlatformActions {
        val revision = MutableStateFlow(0L)
        val sync = MutableStateFlow(SyncStatus())
        val prices = MutableStateFlow(PriceRefreshPhase.IDLE)
        return Proxy.newProxyInstance(
            PlatformActions::class.java.classLoader,
            arrayOf(PlatformActions::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "toString" -> "Manual entry test host"
                "getDispatcher" -> Dispatchers.Unconfined
                "getDataRevision" -> revision
                "getSyncStatus" -> sync
                "getPriceRefreshStatus" -> prices
                "backgroundRefreshEnabled" -> false
                else -> error("Unexpected host call in manual-entry form: ${method.name}")
            }
        } as PlatformActions
    }
}
