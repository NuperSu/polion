package io.valroot.core.application

import io.valroot.core.model.AssetId

/** User preferences: onboarding, appearance, refresh and lock settings, the dashboard and general preferences. */
class SettingsService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun automaticRefresh(): Boolean? = setting("refresh_automatic")?.toBooleanStrictOrNull()

    fun wifiOnlyRefresh(): Boolean = setting("refresh_wifi_only") == "true"

    fun setRefreshPreferences(
        automatic: Boolean,
        wifiOnly: Boolean,
    ) = db.transaction {
        putSetting("refresh_automatic", automatic.toString())
        putSetting("refresh_wifi_only", wifiOnly.toString())
    }

    /** Null preserves entry for populated installations and newer saved setup versions. */
    fun onboarding(): io.valroot.core.onboarding.OnboardingState? =
        io.valroot.core.onboarding
            .OnboardingRepository(db)
            .load()

    fun updateOnboarding(action: io.valroot.core.onboarding.OnboardingAction): io.valroot.core.onboarding.OnboardingState =
        io.valroot.core.onboarding
            .OnboardingRepository(db)
            .update(action)

    /** Presentation preference is local and included in portable backups. */
    fun theme(): String = setting("appearance_theme")?.takeIf { it in setOf("system", "light", "dark") } ?: "dark"

    fun setTheme(theme: String) {
        require(theme in setOf("system", "light", "dark")) { "Invalid appearance theme" }
        putSetting("appearance_theme", theme)
    }

    fun assetList(): AssetListPreference {
        val parts = setting("asset_list_preference")?.split(':') ?: return AssetListPreference()
        if (parts.size !in 2..3 || parts.drop(1).any { it !in setOf("0", "1") }) return AssetListPreference()
        val sort = AssetSort.entries.firstOrNull { it.name == parts[0] } ?: return AssetListPreference()
        return AssetListPreference(sort, parts[1] == "1", parts.getOrNull(2) == "1")
    }

    fun setAssetList(preference: AssetListPreference) {
        putSetting(
            "asset_list_preference",
            "${preference.sort.name}:${if (preference.hideEmpty) 1 else 0}:${if (preference.hideSmall) 1 else 0}",
        )
    }

    /** How long the app may stay in the background before the privacy lock applies; null means the host default. */
    fun lockDelayMillis(): Long? = marketFeeds.deviceSetting("lock_delay")?.toLongOrNull()?.takeIf { it in LOCK_DELAYS }

    /** A device setting: it is not part of backups, like the lock itself. */
    fun setLockDelay(millis: Long) {
        require(millis in LOCK_DELAYS) { "Unsupported lock delay" }
        marketFeeds.putDeviceSetting("lock_delay", millis.toString())
    }

    /** Display and accounting preferences from Settings; stored in `application_settings`, so backups include them. */
    fun general(): GeneralPreferences =
        GeneralPreferences(
            landing = LandingPage.entries.firstOrNull { it.name == setting("landing_page") } ?: LandingPage.MAIN,
            secondaryCurrency = setting("secondary_currency")?.takeIf { it in DISPLAY_CURRENCIES }?.let(::AssetId),
            showPortfolioChart = setting("show_portfolio_chart") != "false",
            zeroBasisRewards = setting("zero_basis_rewards") == "true",
            deviceName = setting("device_name"),
            hideAmounts = setting("hide_amounts") == "true",
            candleCharts = setting("chart_style") == "candles",
        )

    fun setLandingPage(page: LandingPage) = putSetting("landing_page", page.name)

    /** Null removes the secondary currency. Automatic price refresh also fetches the chosen currency. */
    fun setSecondaryCurrency(currency: AssetId?) =
        db.transaction {
            if (currency == null) {
                db.execute("DELETE FROM application_settings WHERE key='secondary_currency'")
            } else {
                require(currency.value in DISPLAY_CURRENCIES) { "Unsupported currency" }
                putSetting("secondary_currency", currency.value)
            }
            putSetting("automatic_price_generation", app.prices.requestNonce())
        }

    fun setShowPortfolioChart(show: Boolean) = putSetting("show_portfolio_chart", show.toString())

    fun setHideAmounts(hide: Boolean) = putSetting("hide_amounts", hide.toString())

    fun setCandleCharts(candles: Boolean) = putSetting("chart_style", if (candles) "candles" else "line")

    /**
     * Acquisitions recorded as rewards (mining, staking, forks, airdrops) count with zero cost basis instead of their
     * recorded value. The recorded value is kept, so turning this off restores it; reports replay with the current choice.
     */
    fun setZeroBasisRewards(enabled: Boolean) = putSetting("zero_basis_rewards", enabled.toString())

    /** Null or blank restores the host's default device name. */
    fun setDeviceName(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            db.execute("DELETE FROM application_settings WHERE key='device_name'")
        } else {
            require(trimmed.length <= 40 && trimmed.none { it.isISOControl() }) { "Use up to 40 characters on one line" }
            putSetting("device_name", trimmed)
        }
    }

    fun dashboardWidgets(): List<DashboardWidgetPreference> {
        val saved = setting("dashboard_widgets") ?: return DashboardWidget.entries.map { DashboardWidgetPreference(it, true) }
        val entries =
            saved.split(',').mapNotNull { token ->
                val parts = token.split(':')
                val widget = DashboardWidget.entries.firstOrNull { it.name == parts.firstOrNull() }
                if (parts.size != 2 || widget == null || parts[1] !in setOf("1", "0")) {
                    null
                } else {
                    DashboardWidgetPreference(widget, parts[1] == "1")
                }
            }
        return if (entries.size == DashboardWidget.entries.size && entries.map { it.widget }.toSet().size == entries.size) {
            entries
        } else {
            DashboardWidget.entries.map { DashboardWidgetPreference(it, true) }
        }
    }

    fun setDashboardWidgets(widgets: List<DashboardWidgetPreference>) {
        require(widgets.size == DashboardWidget.entries.size && widgets.map { it.widget }.toSet() == DashboardWidget.entries.toSet())
        putSetting("dashboard_widgets", widgets.joinToString(",") { "${it.widget.name}:${if (it.visible) 1 else 0}" })
    }
}
