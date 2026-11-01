package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptiveAppearanceTest {
    @Test
    fun compactAndRailDestinationsPreserveAllWorkflows() {
        assertEquals(listOf(Page.HOME, Page.OVERVIEW, Page.REPORTS), compactDestinations)
        assertEquals(Page.OVERVIEW, compactDestination(Page.ACCOUNTS))
        assertEquals(Page.REPORTS, compactDestination(Page.REPORTS))
        assertEquals(Page.OVERVIEW, compactDestination(Page.SETTINGS))
        assertEquals(Page.OVERVIEW, compactDestination(Page.OVERVIEW))
        assertEquals(compactDestinations, railDestinations)
        assertFalse(ProductPresentation(page = Page.HOME).showsHistory)
        assertTrue(ProductPresentation(page = Page.OVERVIEW).showsHistory)
        assertEquals(ProductDialog.ADD, ProductDialog.fromKey("add"))
    }

    @Test
    fun appearanceKeysDefaultToDarkAndPaletteChangesAtRuntime() {
        assertEquals(AppearanceTheme.DARK, AppearanceTheme.fromKey("unknown"))
        assertEquals(AppearanceTheme.SYSTEM, AppearanceTheme.fromKey("system"))
        assertEquals(AppearanceTheme.LIGHT, AppearanceTheme.fromKey("light"))
        runDesktopComposeUiTest(width = 100, height = 100) {
            var appearance by mutableStateOf(AppearanceTheme.LIGHT)
            setContent {
                PolionTheme(appearance) {
                    Box(Modifier.size(100.dp).testTag("sample").background(MaterialTheme.colorScheme.background))
                }
            }
            val light = onNodeWithTag("sample").captureToImage().toPixelMap()[10, 10]
            runOnIdle { appearance = AppearanceTheme.DARK }
            val dark = onNodeWithTag("sample").captureToImage().toPixelMap()[10, 10]
            assertTrue(light.luminance() > 0.8f)
            assertTrue(dark.luminance() < 0.1f)
        }
    }
}
