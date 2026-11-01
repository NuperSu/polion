package io.polion.ui

import io.polion.core.application.ActivityFilter
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.ledger.ActivityKind
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.PortfolioId
import io.polion.core.ownership.Account
import io.polion.core.ownership.TrackingTarget
import io.polion.core.reconcile.EffectiveActivity

internal enum class Page(
    val label: String,
    val glyph: String,
) {
    HOME("Main", "⌂"),
    OVERVIEW("Portfolio", "◈"),
    ACTIVITY("Activity", "⇄"),
    ACCOUNTS("Accounts", "▣"),
    REPORTS("Insights", "◷"),
    SETTINGS("Settings", "⚙"),
    MORE("Advanced tools", "⋯"),
}

internal enum class ProductDialog(
    val key: String,
) {
    ADD("add"),
    ACCOUNT("account"),
    ACTIVITY("activity"),
    BACKUP("backup"),
    GROUP("group"),
    LOCK("lock"),
    OWNER("owner"),
    PORTFOLIO("portfolio"),
    PRICE("price"),
    RECONCILE("reconcile"),
    RESTORE("restore"),
    RULE("rule"),
    SYNC("sync"),
    UTXO("utxo"),
    WALLET("wallet"),
    WIPE("wipe"),
    ;

    companion object {
        fun fromKey(key: String?): ProductDialog? = key?.let { value -> entries.single { it.key == value } }
    }
}

internal data class ProductPresentation(
    val page: Page = Page.HOME,
    val search: String = "",
    val kind: ActivityKind? = null,
    val activityStatus: io.polion.core.ledger.ActivityStatus? = null,
    val activityNetwork: String? = null,
    val activityAsset: AssetId? = null,
    val offset: Int = 0,
    val selectedAccount: AccountId? = null,
    val selectedPortfolio: PortfolioId? = null,
    val period: Int = 30,
    val dialog: ProductDialog? = null,
    val snapshot: PortfolioSnapshot? = null,
    val snapshotFilter: ActivityFilter = ActivityFilter(),
    val chart: List<HistoryPoint> = emptyList(),
    val snapshotLoading: Boolean = false,
    val chartLoading: Boolean = true,
    val chartError: Boolean = false,
    val revision: Int = 0,
    val busy: Boolean = false,
    val sourceDraftEndpoint: String? = null,
    val sourceDraftConsent: Boolean? = null,
    val selectedActivity: EffectiveActivity? = null,
    val addressWallet: TrackingTarget? = null,
    val removeAccount: Account? = null,
    val selectedAsset: AssetValuation? = null,
    val detailAsset: AssetId? = null,
) {
    fun requestedFilter(): ActivityFilter =
        if (page == Page.ACTIVITY) {
            ActivityFilter(
                search = search,
                account = selectedAccount,
                asset = activityAsset,
                kind = kind,
                status = activityStatus,
                network = activityNetwork,
                includeNetworkChoices = true,
                offset = offset,
                portfolio = selectedPortfolio,
            )
        } else {
            ActivityFilter(portfolio = if (page == Page.HOME) null else selectedPortfolio)
        }

    val showsHistory: Boolean get() = page == Page.OVERVIEW || page == Page.REPORTS
}

internal sealed interface ProductEvent {
    data class Navigate(
        val page: Page,
    ) : ProductEvent

    data class Search(
        val value: String,
    ) : ProductEvent

    data class Kind(
        val value: ActivityKind?,
    ) : ProductEvent

    data class Status(
        val value: io.polion.core.ledger.ActivityStatus?,
    ) : ProductEvent

    data class Network(
        val value: String?,
    ) : ProductEvent

    data class Account(
        val value: AccountId?,
    ) : ProductEvent

    data class AssetActivity(
        val asset: AssetId,
    ) : ProductEvent

    data object ClearActivityAsset : ProductEvent

    data class Portfolio(
        val value: PortfolioId?,
    ) : ProductEvent

    data class Offset(
        val value: Int,
    ) : ProductEvent

    data class Period(
        val days: Int,
    ) : ProductEvent

    data class Dialog(
        val value: ProductDialog?,
    ) : ProductEvent
}

internal fun ProductPresentation.reduce(event: ProductEvent): ProductPresentation =
    when (event) {
        is ProductEvent.Navigate ->
            copy(
                page = event.page,
                offset = 0,
                chartLoading =
                    if (event.page !=
                        page
                    ) {
                        event.page == Page.OVERVIEW || event.page == Page.REPORTS
                    } else {
                        chartLoading
                    },
                chartError =
                    if (event.page !=
                        page
                    ) {
                        false
                    } else {
                        chartError
                    },
            )
        is ProductEvent.Search -> copy(search = event.value, offset = 0)
        is ProductEvent.Kind -> copy(kind = event.value, offset = 0)
        is ProductEvent.Status -> copy(activityStatus = event.value, offset = 0)
        is ProductEvent.Network -> copy(activityNetwork = event.value, offset = 0)
        is ProductEvent.Account -> copy(selectedAccount = event.value, offset = 0)
        is ProductEvent.AssetActivity ->
            copy(
                page = Page.ACTIVITY,
                activityAsset = event.asset,
                search = "",
                kind = null,
                activityStatus = null,
                activityNetwork = null,
                selectedAccount = null,
                offset = 0,
                selectedAsset = null,
                detailAsset = null,
            )
        ProductEvent.ClearActivityAsset -> copy(activityAsset = null, offset = 0)
        is ProductEvent.Portfolio ->
            copy(
                selectedPortfolio = event.value,
                chartLoading =
                    if (selectedPortfolio !=
                        event.value
                    ) {
                        showsHistory
                    } else {
                        chartLoading
                    },
                chartError = if (selectedPortfolio != event.value) false else chartError,
            )
        is ProductEvent.Offset -> copy(offset = event.value.also { require(it >= 0) })
        is ProductEvent.Period ->
            copy(
                period = event.days.also { require(it in setOf(0, 1, 7, 30, 90, 365)) },
                chartLoading =
                    if (period !=
                        event.days
                    ) {
                        showsHistory
                    } else {
                        chartLoading
                    },
                chartError = if (period != event.days) false else chartError,
            )
        is ProductEvent.Dialog -> copy(dialog = event.value)
    }
