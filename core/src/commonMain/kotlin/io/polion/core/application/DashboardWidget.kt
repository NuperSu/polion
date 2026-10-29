package io.polion.core.application

enum class DashboardWidget { ALLOCATION, ASSETS, HEALTH, ACTIVITY }

data class DashboardWidgetPreference(
    val widget: DashboardWidget,
    val visible: Boolean,
)
