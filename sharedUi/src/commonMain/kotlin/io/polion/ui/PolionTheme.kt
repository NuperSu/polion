package io.polion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class AppearanceTheme(
    val key: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromKey(key: String): AppearanceTheme = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

internal object PolionSpacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val compactGutter = 16.dp
    val wideGutter = 24.dp
    val navigationWidth = 176.dp
    val wideNavigationWidth = 216.dp
}

internal data class FinancialColors(
    val gain: Color,
    val loss: Color,
    val warning: Color,
    val warningSurface: Color,
)

private val lightColors =
    lightColorScheme(
        primary = Color(0xFF267969),
        onPrimary = Color.White,
        background = Color(0xFFF6F7F3),
        surface = Color.White,
        onSurface = Color(0xFF183A36),
        onBackground = Color(0xFF183A36),
        onSurfaceVariant = Color(0xFF52665F),
        outline = Color(0xFFCAD5CE),
        primaryContainer = Color(0xFFDFF0E7),
        onPrimaryContainer = Color(0xFF183A36),
    )
private val darkColors =
    darkColorScheme(
        primary = Color(0xFF85D5BC),
        onPrimary = Color(0xFF073A2D),
        background = Color(0xFF101C19),
        surface = Color(0xFF192A25),
        onSurface = Color(0xFFE0EEE7),
        onBackground = Color(0xFFE0EEE7),
        onSurfaceVariant = Color(0xFFB3C7BC),
        outline = Color(0xFF425B50),
        primaryContainer = Color(0xFF294C40),
        onPrimaryContainer = Color(0xFFD2F2E2),
    )
private val localFinancialColors =
    staticCompositionLocalOf {
        FinancialColors(Color(0xFF267969), Color(0xFFAD342D), Color(0xFF87521C), Color(0xFFFFF0DE))
    }

@Composable
internal fun PolionTheme(
    appearance: AppearanceTheme,
    content: @Composable () -> Unit,
) {
    val dark = appearance == AppearanceTheme.DARK || (appearance == AppearanceTheme.SYSTEM && isSystemInDarkTheme())
    val financial =
        if (dark) {
            FinancialColors(Color(0xFF85D5BC), Color(0xFFFFB4AB), Color(0xFFF0BD80), Color(0xFF49341F))
        } else {
            FinancialColors(Color(0xFF267969), Color(0xFFAD342D), Color(0xFF87521C), Color(0xFFFFF0DE))
        }
    CompositionLocalProvider(localFinancialColors provides financial) {
        MaterialTheme(colorScheme = if (dark) darkColors else lightColors, typography = Typography(), content = content)
    }
}

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outline
internal val Teal: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Mint: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val Orange: Color @Composable get() = localFinancialColors.current.warning
internal val WarningSurface: Color @Composable get() = localFinancialColors.current.warningSurface
internal val FinancialGain: Color @Composable get() = localFinancialColors.current.gain
internal val FinancialLoss: Color @Composable get() = localFinancialColors.current.loss
internal val Hero = Color(0xFF183A36)
internal val Corners = RoundedCornerShape(22.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSelector(
    selected: AppearanceTheme,
    onSelect: (AppearanceTheme) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PolionSpacing.small)) {
        AppearanceTheme.entries.forEach { appearance ->
            FilterChip(
                selected = selected == appearance,
                onClick = { onSelect(appearance) },
                label = { Text(appearance.label) },
                modifier = Modifier.testTag("appearance-${appearance.key}"),
            )
        }
    }
}
