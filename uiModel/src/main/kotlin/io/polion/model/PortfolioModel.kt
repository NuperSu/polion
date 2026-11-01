package io.polion.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val White = Color(0xFFF5F5F5)
private val Muted = Color(0xFF969696)
private val Card = Color(0xFF171717)
private val Pill = Color(0xFF252525)
private val Green = Color(0xFF27D569)
private val Red = Color(0xFFFF3558)
private val Purple = Color(0xFF8057FF)
private val Gold = Color(0xFFFFCE58)

/** Static design fixtures, deliberately independent of portfolio repositories and providers. */
private data class Coin(
    val ticker: String,
    val name: String,
    val price: String,
    val change: String,
    val color: Color,
    val mark: String,
)

private val coins =
    listOf(
        Coin("BTC", "Bitcoin", "$78,035.62", "+0.99%", Color(0xFFFF9910), "₿"),
        Coin("ETH", "Ethereum", "$2,271.91", "+1.34%", Color(0xFF637EE9), ""),
        Coin("XRP", "XRP", "$1.25", "+0.71%", Color(0xFF242424), ""),
        Coin("SOL", "Solana", "$184.32", "−0.10%", Color(0xFF2EC6B7), ""),
        Coin("BNB", "BNB", "$705.88", "+0.69%", Color(0xFFF4BB25), ""),
        Coin("HYPE", "Hyperliquid", "$38.42", "−0.06%", Color(0xFF073A36), "≈"),
        Coin("USDT", "Tether", "$1.00", "+0.06%", Color(0xFF269E82), "₮"),
    )

enum class ModelPage(
    val label: String,
) {
    HOME("Home"),
    MARKETS("Markets"),
    PORTFOLIO("Portfolio"),
    INSIGHTS("Insights"),
    FOLLOWING("Following"),
}

/** Only preview navigation and chart-range selection change; all figures are illustrative. */
@Composable
fun PortfolioModel(initialPage: ModelPage = ModelPage.PORTFOLIO) {
    var page by remember { mutableStateOf(initialPage) }
    MaterialTheme(
        colorScheme = darkColorScheme(background = Color.Black, surface = Card, onSurface = White, primary = Green),
        typography = Typography(bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, letterSpacing = 0.sp)),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val wide = maxWidth >= 850.dp
                val short = maxHeight < 850.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) SideNavigation(page) { page = it }
                    Column(Modifier.weight(1f)) {
                        ModelHeader(page, wide)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            key(page) {
                                Column(
                                    Modifier
                                        .widthIn(max = if (wide) 1120.dp else 680.dp)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .testTag("model-content")
                                        .padding(horizontal = if (wide) 32.dp else 16.dp)
                                        .padding(top = 12.dp, bottom = 36.dp),
                                    verticalArrangement = Arrangement.spacedBy(32.dp),
                                ) {
                                    when (page) {
                                        ModelPage.HOME -> HomeContent(wide) { page = ModelPage.PORTFOLIO }
                                        ModelPage.MARKETS -> MarketsContent(wide)
                                        ModelPage.PORTFOLIO -> PortfolioContent(wide, short)
                                        ModelPage.INSIGHTS -> InsightsContent(wide)
                                        ModelPage.FOLLOWING -> FollowingContent()
                                    }
                                    Text("POLION · DESIGN PREVIEW · SAMPLE DATA", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                                }
                            }
                            if (page == ModelPage.PORTFOLIO) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(20.dp)
                                        .size(54.dp)
                                        .background(White, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", fontSize = 35.sp, color = Color.Black)
                                }
                            }
                        }
                        if (!wide) BottomNavigation(page) { page = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelHeader(
    page: ModelPage,
    wide: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        ModelIcon("menu", Modifier.size(24.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                if (page ==
                    ModelPage.PORTFOLIO
                ) {
                    "Portfolios"
                } else {
                    page.label
                },
                color = White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (wide) Text("Main Portfolio", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 24.dp))
        ModelIcon("search", Modifier.size(24.dp))
    }
}

@Composable
private fun BottomNavigation(
    page: ModelPage,
    select: (ModelPage) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(Color.Black).padding(top = 12.dp, bottom = 18.dp)) {
        ModelPage.entries.forEach { item ->
            val tint = if (page == item) White else Color(0xFF626262)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab) {
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
) {
    Column(Modifier.width(210.dp).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("polion", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = White, modifier = Modifier.padding(bottom = 34.dp))
        ModelPage.entries.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (page == item) Pill else Color.Transparent)
                    .clickable(role = Role.Tab) { select(item) }
                    .testTag("model-${item.name}")
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelIcon(item.name.lowercase(), Modifier.size(22.dp), if (page == item) White else Muted)
                Text(item.label, fontSize = 14.sp, color = if (page == item) White else Muted)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("UI MODEL\nIllustrative crypto portfolio", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ModelCard(
    modifier: Modifier = Modifier,
    color: Color = Card,
    gap: Int = 14,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}

@Composable
private fun Heading(
    title: String,
    eyebrow: String? = null,
    action: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Text(eyebrow, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
        }
        if (action != null) Capsule(action)
    }
}

@Composable
private fun Capsule(
    text: String,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text,
        color = if (selected) White else Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(if (selected) Pill else Color.Transparent)
                .border(1.dp, Pill, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@Composable
private fun Change(
    value: String,
    badge: Boolean = true,
) {
    val positive = !value.startsWith("−") && !value.startsWith("-")
    val tint = if (positive) Green else Red
    Text(
        value,
        color = tint,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            if (badge) {
                Modifier
                    .clip(
                        RoundedCornerShape(3.dp),
                    ).background(tint.copy(alpha = .17f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            } else {
                Modifier
            },
    )
}

@Composable
private fun CoinLogo(
    coin: Coin,
    size: Int = 32,
) {
    Box(Modifier.size(size.dp).background(coin.color, CircleShape), contentAlignment = Alignment.Center) {
        when (coin.ticker) {
            "ETH" ->
                Canvas(Modifier.size((size * .72f).dp)) {
                    val x = this.size.width / 2
                    val h = this.size.height
                    drawPath(
                        Path().apply {
                            moveTo(x, 0f)
                            lineTo(x * 1.7f, h * .55f)
                            lineTo(x, h * .72f)
                            lineTo(x * .3f, h * .55f)
                            close()
                        },
                        Color(0xFFDCE5FF),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(x, h)
                            lineTo(x * 1.7f, h * .62f)
                            lineTo(x, h * .8f)
                            lineTo(x * .3f, h * .62f)
                            close()
                        },
                        Color(0xFFB1BFFF),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(x, 0f)
                            lineTo(x, h * .72f)
                            lineTo(x * .3f, h * .55f)
                            close()
                        },
                        Color.White,
                    )
                }
            "SOL" ->
                Canvas(Modifier.size((size * .65f).dp)) {
                    repeat(3) { n ->
                        val y =
                            this.size.height * (.14f + n * .3f)
                        drawLine(
                            Color.White,
                            Offset(4f, y),
                            Offset(this.size.width - 4, y),
                            this.size.height * .15f,
                        )
                    }
                }
            "XRP" ->
                Canvas(Modifier.size((size * .65f).dp)) {
                    val w = this.size.width
                    val h = this.size.height
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            cubicTo(w * .4f, h * .6f, w * .6f, h * .6f, w, 0f)
                        },
                        White,
                        style = Stroke(2.dp.toPx()),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(0f, h)
                            cubicTo(w * .4f, h * .4f, w * .6f, h * .4f, w, h)
                        },
                        White,
                        style = Stroke(2.dp.toPx()),
                    )
                }
            "BNB" ->
                Canvas(Modifier.size((size * .58f).dp)) {
                    drawPath(
                        Path().apply {
                            moveTo(this@Canvas.size.width / 2, 0f)
                            lineTo(
                                this@Canvas.size.width,
                                this@Canvas.size.height / 2,
                            )
                            lineTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                            lineTo(
                                0f,
                                this@Canvas.size.height / 2,
                            )
                            close()
                        },
                        White,
                        style = Stroke(3.dp.toPx()),
                    )
                }
            else -> Text(coin.mark, color = White, fontSize = (size * .72f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CoinRow(
    coin: Coin,
    holding: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        CoinLogo(coin)
        Column(Modifier.weight(1f)) {
            Text(coin.ticker, fontWeight = FontWeight.Bold, color = White, fontSize = 17.sp)
            Text(
                if (holding) "0.0009934 | $78,035.04" else coin.name,
                fontSize = 12.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (holding) "$77.52" else coin.price, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = White)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (holding) Text("+0.76", color = Green, fontSize = 13.sp)
                Change(coin.change)
            }
        }
    }
}

@Composable
private fun CoinList(
    items: List<Coin>,
    showAll: Boolean = false,
) {
    ModelCard(gap = 0) {
        items.forEachIndexed { index, coin ->
            CoinRow(coin)
            if (index < items.lastIndex) HorizontalDivider(color = Color(0xFF303030), thickness = .5.dp)
        }
        if (showAll) Capsule("Show All")
    }
}

@Composable
private fun Ranges(labels: List<String> = listOf("1H", "1D", "1W", "1M", "YTD", "1Y", "ALL")) {
    var selected by remember { mutableStateOf("1D") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { label ->
            Text(
                label,
                color = if (label == selected) White else Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(if (label == selected) Pill else Color.Transparent)
                        .clickable { selected = label }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PortfolioContent(
    wide: Boolean,
    short: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Capsule("✧  What's going on?", false)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("77.52", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = White, letterSpacing = (-1).sp)
            Text("USD ⇄", color = Muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("+0.76", color = Green, fontSize = 17.sp)
            Change("+0.99%")
        }
        Spacer(Modifier.height(18.dp))
        LineChart(
            Modifier.fillMaxWidth().height(
                if (wide) {
                    280.dp
                } else if (short) {
                    150.dp
                } else {
                    200.dp
                },
            ),
            Green,
        )
        Ranges()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Capsule("☷")
        Capsule("Highest Holdings  ⌄", false)
    }
    ModelCard(padding = 10) { CoinRow(coins.first(), holding = true) }
    Spacer(Modifier.height(110.dp))
}

@Composable
private fun HomeContent(
    wide: Boolean,
    portfolio: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).background(Green.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
            ModelIcon("bell", Modifier.size(24.dp), Green)
        }
        Column {
            Text("Good morning!", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Check your notifications  ›", color = Muted, fontSize = 14.sp)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (wide) 270.dp else 250.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF2DFF83)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawPath(
                    Path().apply {
                        moveTo(-30f, h * .55f)
                        cubicTo(w * .2f, h * .18f, w * .45f, h * .18f, w * .48f, h * .23f)
                    },
                    Color(0xFF5000E9),
                    style =
                        Stroke(
                            h * .15f,
                            cap = StrokeCap.Round,
                        ),
                )
                drawPath(
                    Path().apply {
                        moveTo(w * .63f, -30f)
                        cubicTo(w * .8f, h * .3f, w * .9f, h * .3f, w + 50f, h * .37f)
                    },
                    Color(0xFFA58FFF),
                    style =
                        Stroke(
                            h * .15f,
                        ),
                )
                drawPath(
                    Path().apply {
                        moveTo(w * .52f, h * .18f)
                        lineTo(w * .61f, h * .27f)
                        lineTo(w * .52f, h * .34f)
                        close()
                    },
                    Color(0xFFFF6136),
                )
            }
            Text("×", color = Color.Black, fontSize = 28.sp, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
            Text(
                "Welcome to your\nPolion Home!",
                color = Color.Black,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            )
        }
        Text("•  ●  •", color = Muted, fontSize = 12.sp)
    }
    Heading("Your Daily Movers", "STAY ON TOP", "↗   ↙")
    Movers()
    Heading("Your Portfolio", "GROWING WEALTH")
    ModelCard(Modifier.clickable(onClick = portfolio)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Main Portfolio ✓", color = White, fontWeight = FontWeight.Bold)
                Text("$77.52", color = White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("1D", color = Muted, fontSize = 12.sp)
                Change("+0.99%")
            }
        }
    }
    Heading("Price Highlights", "LOOKING BACK")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(coins[1], coins[2], coins[0]).forEach { coin ->
            ModelCard(Modifier.width(254.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CoinLogo(coin)
                    Column {
                        Text(coin.ticker, fontWeight = FontWeight.Bold)
                        Text(coin.name, color = Muted, fontSize = 12.sp)
                    }
                }
                Text("In the last year", fontSize = 14.sp)
                Change(if (coin.ticker == "ETH") "−41.01%" else "−12.34%")
                Text(coin.price, fontWeight = FontWeight.Bold)
            }
        }
    }
    Heading("Portfolio Insights", "DIVE DEEPER", "See all")
    Decisions()
    Heading("Upcoming Events", "MARK YOUR CALENDAR")
    ModelCard {
        listOf(
            "4 Nov  Wednesday" to "Ethereum · Community call",
            "10 Nov  Tuesday" to "Solana · Network update",
            "18 Nov  Wednesday" to "Bitcoin · Ecosystem event",
        ).forEach { (day, event) ->
            Text(day, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            HorizontalDivider(color = Pill)
            Text(event, fontSize = 14.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
        }
        Capsule("See all")
    }
    Heading("Trending Crypto", "DISCOVER WHAT'S HOT")
    Trending()
    Heading("What's Moving", "NEW OPPORTUNITIES")
    ModelCard {
        coins.take(3).forEach { coin ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoinLogo(coin)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${coin.ticker}  ${coin.name}", fontWeight = FontWeight.Bold)
                    Change(coin.change, false)
                    Text(
                        "A closer look at today's price movement and the activity across the crypto market.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Text("1 day ago", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Movers() {
    ModelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            listOf(coins[1], coins[0], coins[2], coins[4]).forEachIndexed { index, coin ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(135.dp), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(coin.change, color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Box(
                                Modifier
                                    .width(
                                        30.dp,
                                    ).height(
                                        (105 - index * 26).dp,
                                    ).clip(
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                    ).background(Brush.verticalGradient(listOf(Green, Card))),
                            )
                        }
                    }
                    CoinLogo(coin, 27)
                    Text(coin.ticker, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Trending() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(coins[5], coins[3], coins[4], coins[1]).forEach { coin ->
            ModelCard(Modifier.width(156.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CoinLogo(coin)
                    Text("☆", fontSize = 24.sp)
                }
                Spacer(Modifier.height(4.dp))
                Column {
                    Text(coin.ticker, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(coin.name, color = Muted, fontSize = 12.sp)
                }
                Change(coin.change, false)
            }
        }
    }
}

@Composable
private fun MarketsContent(wide: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Capsule("Overview")
        Capsule("Crypto", false)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Pill)
            .padding(9.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        listOf("All", "Layer 1", "DeFi", "Stablecoins").forEach { Text(it, fontSize = 12.sp, color = if (it == "All") White else Muted) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        coins.take(4).forEach { coin ->
            ModelCard(Modifier.width(if (wide) 210.dp else 128.dp)) {
                Text(coin.ticker, fontWeight = FontWeight.Bold)
                Text(coin.price, fontWeight = FontWeight.Bold)
                Change(coin.change)
                LineChart(Modifier.fillMaxWidth().height(36.dp), if (coin.change.startsWith("−")) Red else Green, labels = false)
            }
        }
    }
    Capsule("✧  Why is Crypto moving?", false)
    Capsule("Followed assets only", false)
    Heading("Most Active")
    CoinList(coins.take(5), true)
    Heading("Top Gainers")
    CoinList(listOf(coins[1], coins[0], coins[2], coins[4]), true)
    Heading("Top Losers")
    CoinList(listOf(coins[3], coins[5]), true)
    Heading("Cryptocurrencies")
    CoinList(coins, true)
}

@Composable
private fun FollowingContent() {
    Row { Capsule("Crypto", false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Capsule("☷  Highest Cap  ⌄", false)
        Row {
            Capsule("1H", false)
            Capsule("1D")
            Capsule("1W", false)
        }
    }
    CoinList(coins.take(5))
    Spacer(Modifier.height(180.dp))
}

@Composable
private fun Decisions() {
    ModelCard(color = Color(0xFF242017)) {
        Text("Decisions", color = Color(0xFFC6B06D), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Main Portfolio", color = White, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("↗  Good\n    decisions", color = Green, fontSize = 15.sp)
            Text("Bad  ↘\ndecisions", color = Red, fontSize = 15.sp)
        }
    }
}

@Composable
private fun InsightsContent(wide: Boolean) {
    Heading("History")
    ModelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Balance", color = Muted, fontSize = 12.sp)
                Text("77.52 $", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Capsule("1Y  ⌄", false)
        }
        LineChart(Modifier.fillMaxWidth().height(180.dp), Red, history = true)
    }
    Heading("Portfolio Performance")
    ModelCard {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val baseline = size.height * .55f
            drawLine(Color(0xFF464646), Offset(0f, baseline), Offset(size.width, baseline), 1f)
            repeat(4) { group ->
                repeat(3) { bar ->
                    val value = listOf(-.22f, -.13f, .48f, -.07f)[group] * (if (bar == 0) .25f else 1f)
                    val x = size.width * (.07f + group * .25f) + bar * 12.dp.toPx()
                    drawRoundRect(
                        listOf(Purple, Color(0xFFFF5C99), Gold)[bar],
                        Offset(
                            x,
                            if (value >
                                0
                            ) {
                                baseline - value * size.height
                            } else {
                                baseline
                            },
                        ),
                        Size(9.dp.toPx(), kotlin.math.abs(value) * size.height),
                        androidx.compose.ui.geometry
                            .CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("Q1 '26", "Q2 '26", "Q3 '26", "Q4 '26").forEach { Text(it, color = Muted, fontSize = 11.sp) }
        }
        Text("● Main Portfolio    ● BTC    ● Crypto market", color = Muted, fontSize = 11.sp)
    }
    Heading("Decisions")
    Decisions()
    Heading("Portfolio Diversity")
    ModelCard {
        Donut("BTC", "100%", Modifier.align(Alignment.CenterHorizontally).size(if (wide) 280.dp else 220.dp))
        Text("● Currency", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    Heading("Gains Reporting")
    ModelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Asset", color = Muted)
            Text("Owned", color = Muted)
            Text("Realized", color = Muted)
            Text("Unrealized", color = Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("₿ BTC", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("0.0009934", fontSize = 11.sp, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
            Text("$0.00", fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text("+$7.52", color = Green, fontSize = 12.sp, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModelCard(Modifier.weight(1f)) {
            Text("Most Used\nExchanges", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(55.dp))
            Text("Self-custody", color = Muted)
            Spacer(Modifier.height(55.dp))
        }
        ModelCard(Modifier.weight(1f)) {
            Text("Asset Location", fontWeight = FontWeight.Bold)
            Donut("Wallet", "100%", Modifier.fillMaxWidth().height(170.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModelCard(Modifier.weight(1f)) {
            Text("Trade Statistics", fontWeight = FontWeight.Bold)
            Text("12", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("/ 3 assets", color = Muted, fontSize = 12.sp)
        }
        ModelCard(Modifier.weight(1f)) {
            Text("Fees", fontWeight = FontWeight.Bold)
            Text("$0.84", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("All time", color = Muted, fontSize = 12.sp)
        }
    }
    Heading("Asset Worth")
    ModelCard {
        LineChart(Modifier.fillMaxWidth().height(160.dp), Purple)
        Text("● BTC", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Donut(
    label: String,
    amount: String,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val diameter = minOf(size.width, size.height) * .78f
            drawArc(
                Purple,
                .5f,
                359f,
                false,
                Offset((size.width - diameter) / 2, (size.height - diameter) / 2),
                Size(diameter, diameter),
                style =
                    Stroke(
                        diameter * .24f,
                    ),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(amount, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun LineChart(
    modifier: Modifier,
    tint: Color,
    history: Boolean = false,
    labels: Boolean = true,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().padding(vertical = if (labels) 20.dp else 0.dp)) {
            val values =
                if (history) {
                    List(65) { i -> if (i < 48) .03f else .8f + sin(i * 2.4f) * .025f }
                } else {
                    listOf(
                        .02f,
                        .04f,
                        .07f,
                        .13f,
                        .23f,
                        .45f,
                        .35f,
                        .27f,
                        .14f,
                        .5f,
                        .53f,
                        .58f,
                        .63f,
                        .59f,
                        .54f,
                        .5f,
                        .49f,
                        .49f,
                        .6f,
                        .48f,
                        .5f,
                        .54f,
                        .59f,
                        .64f,
                        .66f,
                        .71f,
                        .72f,
                        .73f,
                        .8f,
                        .91f,
                        .98f,
                        .97f,
                        .94f,
                    )
                }
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / values.lastIndex
                val y = size.height * (1 - v)
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    val px = size.width * (i - 1) / values.lastIndex
                    val py = size.height * (1 - values[i - 1])
                    path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
                }
            }
            val area =
                Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(area, Brush.verticalGradient(listOf(tint.copy(alpha = .2f), tint.copy(alpha = 0f))))
            if (labels) {
                repeat(2) { row ->
                    val y =
                        size.height * row
                    var x = 0f
                    while (x <
                        size.width
                    ) {
                        drawLine(Muted, Offset(x, y), Offset(x + 1, y), 1f)
                        x += 4.dp.toPx()
                    }
                }
            }
            drawPath(path, tint, style = Stroke(1.6.dp.toPx()))
        }
        if (labels) {
            Text(if (history) "$86.00" else "$77.53", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
            Text(if (history) "$0.00" else "$76.70", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun ModelIcon(
    name: String,
    modifier: Modifier,
    tint: Color = White,
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
            "home" -> {
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
            "portfolio" ->
                drawPath(
                    Path().apply {
                        moveTo(w * .42f, h * .13f)
                        quadraticTo(w * .5f, -h * .05f, w * .6f, h * .13f)
                        lineTo(w * .95f, h * .8f)
                        quadraticTo(
                            w,
                            h * .96f,
                            w * .8f,
                            h * .96f,
                        )
                        lineTo(w * .18f, h * .96f)
                        quadraticTo(0f, h * .96f, w * .08f, h * .8f)
                        close()
                    },
                    tint,
                    style = Stroke(s),
                )
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
