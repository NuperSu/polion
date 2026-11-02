package io.polion.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModelCapsule(
    text: String,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(if (selected) colors.surfaceVariant else Color.Transparent)
                .border(1.dp, colors.surfaceVariant, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}
