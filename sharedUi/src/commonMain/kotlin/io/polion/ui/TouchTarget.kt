package io.polion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Smallest touch target for an interactive control, as recommended for touch input. */
internal val MinimumTouchTarget: Dp = 48.dp

/**
 * Gives a small control a [MinimumTouchTarget] touch and accessibility area without changing its layout footprint.
 *
 * [content] is one composable that is laid out as usual. [target] carries the clickable and semantics modifiers; it is laid
 * out at [MinimumTouchTarget] and centred on the content, so it can overhang the content and neighbouring controls keep their
 * positions. The drawn icon or glyph is unchanged.
 */
@Composable
internal fun TouchTarget(
    target: Modifier,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            content()
            Box(target.requiredSize(MinimumTouchTarget))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val visual = measurables[0].measure(constraints)
        val touch = measurables[1].measure(Constraints())
        layout(visual.width, visual.height) {
            visual.placeRelative(0, 0)
            touch.placeRelative((visual.width - touch.width) / 2, (visual.height - touch.height) / 2)
        }
    }
}
