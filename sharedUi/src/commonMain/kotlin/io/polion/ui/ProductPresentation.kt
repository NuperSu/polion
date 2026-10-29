package io.polion.ui

import io.polion.core.application.ActivityFilter
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.ledger.ActivityKind
import io.polion.core.model.AccountId
import io.polion.core.model.PortfolioId
import io.polion.core.ownership.Account
import io.polion.core.ownership.TrackingTarget
import io.polion.core.reconcile.EffectiveActivity

internal enum class Page(
    val label: String,
    val glyph: String,
) {
    HOME("Home", "⌂"),
    OVERVIEW("Portfolio", "◈"),
    ACTIVITY("Activity", "⇄"),
    ACCOUNTS("Accounts", "▣"),
    REPORTS("Reports", "◷"),
    SETTINGS("Settings", "⚙"),
    MORE("More", "⋯"),
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
    val offset: Int = 0,
    val selectedAccount: AccountId? = null,
    val selectedPortfolio: PortfolioId? = null,
    val period: Int = 30,
    val dialog: ProductDialog? = null,
    val snapshot: PortfolioSnapshot? = null,
    val snapshotFilter: ActivityFilter = ActivityFilter(),
    val chart: List<HistoryPoint> = emptyList(),
    val snapshotLoading: Boolean = false,
    val chartLoading: Boolean = false,
    val revision: Int = 0,
    val busy: Boolean = false,
    val sourceDraftEndpoint: String? = null,
    val sourceDraftConsent: Boolean? = null,
    val selectedActivity: EffectiveActivity? = null,
    val addressWallet: TrackingTarget? = null,
    val removeAccount: Account? = null,
    val selectedAsset: AssetValuation? = null,
) {
    fun requestedFilter(): ActivityFilter =
        if (page == Page.ACTIVITY) {
            ActivityFilter(search = search, account = selectedAccount, kind = kind, offset = offset, portfolio = selectedPortfolio)
        } else {
            ActivityFilter(portfolio = selectedPortfolio)
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

    data class Account(
        val value: AccountId?,
    ) : ProductEvent

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
        is ProductEvent.Navigate -> copy(page = event.page, offset = 0)
        is ProductEvent.Search -> copy(search = event.value, offset = 0)
        is ProductEvent.Kind -> copy(kind = event.value, offset = 0)
        is ProductEvent.Account -> copy(selectedAccount = event.value, offset = 0)
        is ProductEvent.Portfolio -> copy(selectedPortfolio = event.value)
        is ProductEvent.Offset -> copy(offset = event.value.also { require(it >= 0) })
        is ProductEvent.Period -> copy(period = event.days.also { require(it in setOf(0, 1, 7, 30, 90, 365)) })
        is ProductEvent.Dialog -> copy(dialog = event.value)
    }
