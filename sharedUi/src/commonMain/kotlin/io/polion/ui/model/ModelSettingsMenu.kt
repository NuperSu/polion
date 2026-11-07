package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val foreground @Composable get() = MaterialTheme.colorScheme.onSurface
private val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val card @Composable get() = MaterialTheme.colorScheme.surface
private val divider @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val green @Composable get() = MaterialTheme.colorScheme.primary

/** Unavailable rows stay visible but dimmed and inert, so the menu shows what is planned without pretending it works. */
private const val UNAVAILABLE_ALPHA = 0.38f

enum class SettingsIcon { GENERAL, VIEW, PORTFOLIO, NOTIFICATIONS, CONNECTIONS, SECURITY, DATA, SUPPORT }

/** One top-level Settings destination: drawn icon, title and chevron. */
@Composable
fun SettingsMenuRow(
    icon: SettingsIcon,
    title: String,
    tag: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag(tag)
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = green
            Canvas(Modifier.size(26.dp)) { drawSettingsIcon(icon, tint) }
            Spacer(Modifier.width(18.dp))
            Text(title, color = foreground, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Chevron()
        }
        HorizontalDivider(color = divider)
    }
}

/** Back arrow and centered title for a Settings subsection. */
@Composable
fun SettingsSubHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "←",
            color = foreground,
            fontSize = 24.sp,
            modifier = Modifier.clickable(onClick = onBack).testTag("settings-back").padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        )
        Text(title, color = foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = foreground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

/** A labelled value with a chevron, for example "Local Fiat Currency / US dollar (USD)". */
@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    tag: String? = null,
    onClick: () -> Unit = {},
) {
    SettingsRowFrame(enabled, tag, onClick) {
        Column(Modifier.weight(1f)) {
            Text(label, color = muted, fontSize = 13.sp)
            Text(value, color = foreground, fontSize = 18.sp)
        }
        Chevron()
    }
}

/** A plain action row with a chevron, for example "Export Data". */
@Composable
fun SettingsActionRow(
    title: String,
    enabled: Boolean = true,
    tag: String? = null,
    color: Color? = null,
    onClick: () -> Unit = {},
) {
    SettingsRowFrame(enabled, tag, onClick) {
        Text(title, color = color ?: foreground, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Chevron()
    }
}

/** A switch row with an optional explanation underneath its title. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    tag: String? = null,
    onChange: (Boolean) -> Unit = {},
) {
    Column(Modifier.alpha(if (enabled) 1f else UNAVAILABLE_ALPHA)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = foreground, fontSize = 18.sp)
                description?.let { Text(it, color = muted, fontSize = 13.sp, lineHeight = 17.sp) }
            }
            Switch(
                checked,
                if (enabled) onChange else null,
                enabled = enabled,
                modifier = tag?.let { Modifier.testTag(it) } ?: Modifier,
            )
        }
        HorizontalDivider(color = divider)
    }
}

/** A rounded card that groups related rows, as in Connections. */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(card, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

/** Explains why the rows below are dimmed. */
@Composable
fun SettingsNote(text: String) {
    Text(text, color = muted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SettingsRowFrame(
    enabled: Boolean,
    tag: String?,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(Modifier.alpha(if (enabled) 1f else UNAVAILABLE_ALPHA)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        HorizontalDivider(color = divider)
    }
}

@Composable
private fun Chevron() {
    Text("›", color = foreground, fontSize = 26.sp, modifier = Modifier.padding(start = 8.dp))
}

private fun DrawScope.drawSettingsIcon(
    icon: SettingsIcon,
    tint: Color,
) {
    val w = size.width
    val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
    val center = Offset(w / 2, w / 2)
    when (icon) {
        SettingsIcon.GENERAL -> {
            for (i in 0 until 8) {
                val angle = i * PI / 4
                val inner = Offset(center.x + cos(angle).toFloat() * w * 0.3f, center.y + sin(angle).toFloat() * w * 0.3f)
                val outer = Offset(center.x + cos(angle).toFloat() * w * 0.44f, center.y + sin(angle).toFloat() * w * 0.44f)
                drawLine(tint, inner, outer, strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            }
            drawCircle(tint, w * 0.3f, center, style = stroke)
            drawCircle(tint, w * 0.11f, center, style = stroke)
        }
        SettingsIcon.VIEW -> {
            val eye = Path()
            eye.moveTo(w * 0.06f, center.y)
            eye.quadraticTo(center.x, w * 0.12f, w * 0.94f, center.y)
            eye.quadraticTo(center.x, w * 0.88f, w * 0.06f, center.y)
            drawPath(eye, tint, style = stroke)
            drawCircle(tint, w * 0.12f, center, style = stroke)
        }
        SettingsIcon.PORTFOLIO -> {
            drawRoundRect(tint, Offset(w * 0.08f, w * 0.3f), Size(w * 0.84f, w * 0.58f), CornerRadius(w * 0.08f), style = stroke)
            drawRoundRect(tint, Offset(w * 0.34f, w * 0.12f), Size(w * 0.32f, w * 0.18f), CornerRadius(w * 0.05f), style = stroke)
        }
        SettingsIcon.NOTIFICATIONS -> {
            val bell = Path()
            bell.moveTo(w * 0.18f, w * 0.72f)
            bell.lineTo(w * 0.82f, w * 0.72f)
            bell.lineTo(w * 0.74f, w * 0.6f)
            bell.lineTo(w * 0.74f, w * 0.42f)
            bell.quadraticTo(w * 0.72f, w * 0.14f, center.x, w * 0.14f)
            bell.quadraticTo(w * 0.28f, w * 0.14f, w * 0.26f, w * 0.42f)
            bell.lineTo(w * 0.26f, w * 0.6f)
            bell.close()
            drawPath(bell, tint, style = stroke)
            drawLine(tint, Offset(w * 0.42f, w * 0.86f), Offset(w * 0.58f, w * 0.86f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        SettingsIcon.CONNECTIONS -> {
            drawLine(tint, Offset(w * 0.38f, w * 0.62f), Offset(w * 0.62f, w * 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawArc(tint, 90f, 180f, false, Offset(w * 0.08f, w * 0.46f), Size(w * 0.4f, w * 0.4f), style = stroke)
            drawArc(tint, 270f, 180f, false, Offset(w * 0.52f, w * 0.14f), Size(w * 0.4f, w * 0.4f), style = stroke)
            drawLine(tint, Offset(w * 0.28f, w * 0.46f), Offset(w * 0.46f, w * 0.28f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.54f, w * 0.72f), Offset(w * 0.72f, w * 0.54f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        SettingsIcon.SECURITY -> {
            drawRoundRect(tint, Offset(w * 0.2f, w * 0.44f), Size(w * 0.6f, w * 0.46f), CornerRadius(w * 0.08f), style = stroke)
            drawArc(tint, 180f, 180f, false, Offset(w * 0.3f, w * 0.1f), Size(w * 0.4f, w * 0.5f), style = stroke)
        }
        SettingsIcon.DATA -> {
            drawArc(tint, 200f, 230f, false, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), style = stroke)
            drawLine(tint, Offset(w * 0.78f, w * 0.12f), Offset(w * 0.8f, w * 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.8f, w * 0.34f), Offset(w * 0.58f, w * 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        SettingsIcon.SUPPORT -> {
            drawCircle(tint, w * 0.4f, center, style = stroke)
            drawArc(tint, 200f, 220f, false, Offset(w * 0.36f, w * 0.24f), Size(w * 0.28f, w * 0.26f), style = stroke)
            drawLine(tint, Offset(center.x, w * 0.5f), Offset(center.x, w * 0.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(tint, w * 0.04f, Offset(center.x, w * 0.74f))
        }
    }
}
