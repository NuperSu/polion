package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.ui.model.ModelPortfolioSummary
import io.polion.ui.model.ModelPortfolioSummaryState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val White = Color(0xFFF5F5F5)
private val Muted = Color(0xFF969696)
private val Card = Color(0xFF171717)
private val Pill = Color(0xFF252525)
private val Green = Color(0xFF27D569)

enum class ModelPage(
    val label: String,
) {
    MAIN("Main"),
    PORTFOLIO("Portfolio"),
    INSIGHTS("Insights"),
}

data class ModelShellState(
    val page: ModelPage,
    val title: String,
    val showBack: Boolean = false,
    val refreshing: Boolean = false,
    val menuItems: List<String> = listOf("Accounts", "Settings"),
    val sidebarFooter: String? = null,
    val dark: Boolean = true,
    val refreshQueued: Boolean = false,
)

@Composable
fun ModelTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme =
            if (dark) {
                darkColorScheme(
                    background = Color.Black,
                    surface = Card,
                    onSurface = White,
                    primary = Green,
                    surfaceVariant = Pill,
                    onSurfaceVariant = Muted,
                    tertiary = Color(0xFF8057FF),
                    secondary = Color(0xFFFF5C99),
                    tertiaryContainer = Color(0xFFFFCE58),
                    secondaryContainer = Color(0xFF242017),
                    onSecondaryContainer = Color(0xFFC6B06D),
                    error = Color(0xFFFF3558),
                    outlineVariant = Color(0xFF303030),
                )
            } else {
                lightColorScheme(
                    background = Color(0xFFF7F7F7),
                    surface = Color.White,
                    onSurface = Color(0xFF171717),
                    primary = Color(0xFF087B39),
                    surfaceVariant = Color(0xFFE8E8E8),
                    onSurfaceVariant = Color(0xFF666666),
                )
            },
        typography = Typography(bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, letterSpacing = 0.sp)),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelShell(
    state: ModelShellState,
    onPage: (ModelPage) -> Unit,
    onBack: () -> Unit,
    onMenu: (String) -> Unit,
    onRefresh: () -> Unit,
    onAdd: (() -> Unit)? = null,
    content: @Composable ColumnScope.(wide: Boolean, short: Boolean) -> Unit,
) {
    ModelTheme(state.dark) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val wide = maxWidth >= 850.dp
                val short = maxHeight < 850.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) SideNavigation(state.page, onPage, state.sidebarFooter)
                    Column(Modifier.weight(1f)) {
                        ModelHeader(
                            state.title,
                            state.showBack,
                            onBack,
                            onMenu,
                            onRefresh,
                            state.menuItems,
                            state.refreshQueued,
                            state.refreshing,
                        )
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = { if (!state.refreshQueued && !state.refreshing) onRefresh() },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            key(state.page, state.title) {
                                Column(
                                    Modifier
                                        .widthIn(max = if (wide) 1120.dp else 680.dp)
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter)
                                        .verticalScroll(rememberScrollState())
                                        .testTag("model-content")
                                        .padding(horizontal = if (wide) 32.dp else 16.dp)
                                        .padding(top = 12.dp, bottom = 100.dp),
                                    verticalArrangement = Arrangement.spacedBy(28.dp),
                                ) { content(wide, short) }
                            }
                            if (onAdd != null) {
                                Box(
                                    Modifier
                                        .align(
                                            Alignment.BottomEnd,
                                        ).padding(20.dp)
                                        .size(54.dp)
                                        .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable(
                                            onClick = onAdd,
                                        ).semantics { contentDescription = "Add transaction" }
                                        .testTag("add-transaction"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", fontSize = 35.sp, color = MaterialTheme.colorScheme.background)
                                }
                            }
                        }
                        if (!wide) BottomNavigation(state.page, onPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelHeader(
    title: String,
    back: Boolean,
    onBack: () -> Unit,
    menu: (String) -> Unit,
    refresh: () -> Unit,
    menuItems: List<String>,
    refreshQueued: Boolean,
    refreshing: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier
                    .size(40.dp)
                    .clickable {
                        if (back) onBack() else expanded = true
                    }.semantics { contentDescription = if (back) "Back" else "Menu" }
                    .testTag(if (back) "model-back" else "model-menu"),
                contentAlignment = Alignment.Center,
            ) {
                if (back) Text("‹", fontSize = 32.sp) else ModelIcon("menu", Modifier.size(24.dp), MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded, { expanded = false }) {
                menuItems.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        expanded = false
                        menu(name)
                    })
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
        TextButton(
            onClick = refresh,
            enabled = !refreshQueued && !refreshing,
            modifier =
                Modifier
                    .semantics {
                        contentDescription =
                            when {
                                refreshQueued -> "Price refresh queued"
                                refreshing -> "Refreshing prices"
                                else -> "Refresh prices"
                            }
                    }.testTag("model-refresh"),
        ) {
            when {
                refreshQueued -> Text("Queued", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                refreshing -> Text("Refreshing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                else -> Text("↻", color = MaterialTheme.colorScheme.onSurface, fontSize = 25.sp)
            }
        }
    }
}

@Composable
private fun BottomNavigation(
    page: ModelPage,
    select: (ModelPage) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 12.dp, bottom = 18.dp)) {
        ModelPage.entries.forEach { item ->
            val tint = if (page == item) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .selectable(selected = page == item, role = Role.Tab) {
                        select(item)
                    }.testTag(
                        "model-${item.name}",
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ModelIcon(item.name.lowercase(), Modifier.size(26.dp), tint)
                Text(item.label, color = tint, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SideNavigation(
    page: ModelPage,
    select: (ModelPage) -> Unit,
    footer: String?,
) {
    Column(Modifier.width(210.dp).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "polion",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 34.dp),
        )
        ModelPage.entries.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (page == item) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .selectable(selected = page == item, role = Role.Tab) { select(item) }
                    .testTag("model-${item.name}")
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelIcon(
                    item.name.lowercase(),
                    Modifier.size(22.dp),
                    if (page ==
                        item
                    ) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    item.label,
                    fontSize = 14.sp,
                    color =
                        if (page ==
                            item
                        ) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        footer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
    }
}

@Composable
private fun ModelIcon(
    name: String,
    modifier: Modifier,
    tint: Color,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = 1.7.dp.toPx()

        fun line(
            x: Float,
            y: Float,
            xx: Float,
            yy: Float,
        ) = drawLine(tint, Offset(x * w, y * h), Offset(xx * w, yy * h), s, StrokeCap.Round)
        when (name) {
            "bell" -> {
                drawPath(
                    Path().apply {
                        moveTo(w * .2f, h * .7f)
                        lineTo(w * .25f, h * .35f)
                        cubicTo(w * .25f, h * .05f, w * .75f, h * .05f, w * .75f, h * .35f)
                        lineTo(
                            w * .8f,
                            h * .7f,
                        )
                        close()
                    },
                    tint,
                )
                drawCircle(tint, w * .09f, Offset(w * .5f, h * .86f))
            }
            "menu" -> repeat(3) { line(.08f, .22f + it * .27f, .9f, .22f + it * .27f) }
            "search" -> {
                drawCircle(tint, w * .3f, Offset(w * .4f, h * .4f), style = Stroke(s))
                line(.63f, .64f, .95f, .97f)
            }
            "main" -> {
                line(.08f, .44f, .5f, .06f)
                line(.5f, .06f, .92f, .44f)
                line(.2f, .35f, .2f, .94f)
                line(.8f, .35f, .8f, .94f)
                line(.2f, .94f, .4f, .94f)
                line(.6f, .94f, .8f, .94f)
                line(.4f, .94f, .4f, .61f)
                line(.4f, .61f, .6f, .61f)
                line(.6f, .61f, .6f, .94f)
            }
            "markets" -> {
                repeat(3) { i ->
                    val x =
                        .15f + i * .28f
                    val top =
                        .65f - i * .2f
                    line(x, .94f, x, top)
                    line(x, top, x + .13f, top)
                    line(x + .13f, top, x + .13f, .94f)
                }
                line(.04f, .55f, .84f, .05f)
                line(.57f, .05f, .84f, .05f)
                line(.84f, .05f, .84f, .31f)
            }
            "portfolio" -> {
                drawRoundRect(
                    tint,
                    Offset(w * .08f, h * .2f),
                    Size(w * .84f, h * .67f),
                    androidx.compose.ui.geometry
                        .CornerRadius(w * .08f),
                    style = Stroke(s),
                )
                drawRoundRect(
                    tint,
                    Offset(w * .57f, h * .42f),
                    Size(w * .36f, h * .26f),
                    androidx.compose.ui.geometry
                        .CornerRadius(w * .04f),
                    style = Stroke(s),
                )
                drawCircle(tint, w * .025f, Offset(w * .68f, h * .55f))
                line(.15f, .2f, .71f, .06f)
                line(.71f, .06f, .83f, .2f)
            }
            "insights" -> {
                drawCircle(tint, w * .42f, style = Stroke(s))
                line(.5f, .08f, .5f, .92f)
                line(.08f, .35f, .5f, .52f)
            }
            "following" -> {
                val path = Path()
                repeat(10) { i ->
                    val angle =
                        -PI / 2 + i * PI / 5
                    val r =
                        if (i % 2 ==
                            0
                        ) {
                            w * .48
                        } else {
                            w * .21
                        }
                    ; val x =
                        w / 2 + cos(angle).toFloat() * r.toFloat()
                    val y =
                        h / 2 + sin(angle).toFloat() * r.toFloat()
                    if (i ==
                        0
                    ) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path, tint, style = Stroke(s))
            }
        }
    }
}
