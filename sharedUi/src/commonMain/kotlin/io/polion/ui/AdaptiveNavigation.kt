package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val compactDestinations = listOf(Page.HOME, Page.OVERVIEW, Page.REPORTS)
internal val railDestinations = compactDestinations

internal fun compactDestination(page: Page): Page = if (page in compactDestinations) page else Page.OVERVIEW

@Composable
internal fun CompactNavigation(
    selected: Page,
    onSelect: (Page) -> Unit,
    onAdd: () -> Unit,
) {
    NavigationBar(
        modifier = Modifier.testTag("compact-navigation"),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        compactDestinations.forEach { page ->
            NavigationBarItem(
                modifier = Modifier.testTag("destination-${page.name}"),
                selected = compactDestination(selected) == page,
                onClick = { onSelect(page) },
                icon = {
                    RevisedIcon(
                        when (page) {
                            Page.HOME -> "main"
                            Page.OVERVIEW -> "portfolio"
                            else -> "insights"
                        },
                        Modifier.size(24.dp),
                    )
                },
                label = { Text(page.label, fontSize = 10.sp, maxLines = 2) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Mint),
            )
        }
    }
}

@Composable
internal fun WorkspaceRail(
    selected: Page,
    onSelect: (Page) -> Unit,
    wide: Boolean,
    onAdd: () -> Unit,
) {
    Column(
        Modifier
            .width(if (wide) PolionSpacing.wideNavigationWidth else PolionSpacing.navigationWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("workspace-rail")
            .verticalScroll(rememberScrollState())
            .padding(PolionSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(PolionSpacing.small),
    ) {
        Text(
            "polion",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        railDestinations.forEach { page ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected == page) Mint else MaterialTheme.colorScheme.surface)
                    .testTag("destination-${page.name}")
                    .selectable(selected = selected == page, role = Role.Tab, onClick = { onSelect(page) })
                    .semantics { contentDescription = "${page.label} destination" }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RevisedIcon(
                    when (page) {
                        Page.HOME -> "main"
                        Page.OVERVIEW -> "portfolio"
                        else -> "insights"
                    },
                    Modifier.size(24.dp),
                    Teal,
                )
                Text(
                    page.label,
                    modifier = Modifier.padding(start = 12.dp),
                    color = if (selected == page) Ink else Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag("workspace-add-action")) { Text("+ Add") }
        Text("Local first · Your device", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
    }
}
