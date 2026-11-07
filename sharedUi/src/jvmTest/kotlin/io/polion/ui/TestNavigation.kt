package io.polion.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/** The gear opens Settings directly. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openSettings() {
    onNodeWithTag("model-menu").performClick()
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openSettingsRow(tag: String) {
    // Settings rows appear once the portfolio has loaded.
    waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("model-content").performScrollToNode(hasTestTag(tag))
    onNodeWithTag(tag).performClick()
}

/** Settings > Connections lists the accounts; a row opens that account's details. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openConnections() {
    openSettings()
    openSettingsRow("settings-connections")
}

/** The former tools hub is reached from Settings > Devices & Data > Full Transaction History. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openAdvancedTools() {
    openSettings()
    openSettingsRow("settings-data")
    openSettingsRow("settings-history")
    onNodeWithTag("workspace-menu").performClick()
    onAllNodesWithText("Advanced tools").onLast().performClick()
}
