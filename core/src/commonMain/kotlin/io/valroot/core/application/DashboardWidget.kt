package io.valroot.core.application

enum class DashboardWidget { ALLOCATION, ASSETS, HEALTH, ACTIVITY }

data class DashboardWidgetPreference(
    val widget: DashboardWidget,
    val visible: Boolean,
)

enum class AssetSort { VALUE, NAME }

data class AssetListPreference(
    val sort: AssetSort = AssetSort.VALUE,
    val hideEmpty: Boolean = false,
    val hideSmall: Boolean = false,
)
