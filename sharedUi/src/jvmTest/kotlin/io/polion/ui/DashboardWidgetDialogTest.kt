package io.polion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.DashboardWidget
import io.polion.core.application.DashboardWidgetPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DashboardWidgetDialogTest {
    @Test
    fun saveAppliesVisibilityAndOrdering() {
        var saved: List<DashboardWidgetPreference>? = null
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                var draft by androidx.compose.runtime.remember {
                    mutableStateOf(DashboardWidget.entries.map { DashboardWidgetPreference(it, true) })
                }
                PolionTheme(AppearanceTheme.LIGHT) {
                    DashboardWidgetDialog(draft, { draft = it }, {}, { saved = it })
                }
            }
            onNodeWithTag("widget-visible-HEALTH").performClick()
            onNodeWithTag("widget-up-HEALTH").performClick()
            onNodeWithText("Save").performClick()
            waitForIdle()
        }
        assertEquals(DashboardWidget.HEALTH, saved!![1].widget)
        assertFalse(saved!![1].visible)
    }
}
