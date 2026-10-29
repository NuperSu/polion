package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

internal val compactDestinations = listOf(Page.HOME, Page.OVERVIEW, Page.ACTIVITY, Page.MORE)
internal val railDestinations = listOf(Page.HOME, Page.OVERVIEW, Page.ACTIVITY, Page.ACCOUNTS, Page.REPORTS, Page.SETTINGS)

internal fun compactDestination(page: Page): Page = if (page in compactDestinations) page else Page.MORE

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
        listOf(Page.HOME, Page.OVERVIEW, null, Page.ACTIVITY, Page.MORE).forEach { page ->
            NavigationBarItem(
                modifier = Modifier.testTag(if (page == null) "add-action" else "destination-${page.name}"),
                selected = page != null && compactDestination(selected) == page,
                onClick = { if (page == null) onAdd() else onSelect(page) },
                icon = { Text(page?.glyph ?: "+", fontSize = 23.sp) },
                label = { Text(page?.label ?: "Add", fontSize = 10.sp, maxLines = 2) },
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
        Text("WORKSPACE", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(12.dp))
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
                Text(page.glyph, color = Teal, fontSize = 20.sp)
                Text(
                    page.label,
                    modifier = Modifier.padding(start = 12.dp),
                    color = if (selected == page) Ink else Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag("add-action")) { Text("Add source or activity") }
        Text("Local first · Your device", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
    }
}
