package ru.gigapisar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.gigapisar.R
import ru.gigapisar.overlay.FabPreview
import ru.gigapisar.settings.SettingsRepository

/** The floating button's look, for the size preview: same gradient and microphone as the overlay. */
@Composable
internal fun FabIcon(
    diameter: Float,
    modifier: Modifier = Modifier,
    blue: Boolean = false,
) {
    Canvas(modifier = modifier.size(diameter.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0x33000000), r, c.copy(y = c.y + r * 0.05f))
        drawCircle(
            Brush.linearGradient(
                0f to Color(if (blue) 0xFF7FE3F0 else 0xFFA8E063),
                0.55f to Color(if (blue) 0xFF1FA3D6 else 0xFF1FA03A),
                1f to Color(if (blue) 0xFF0B6FA8 else 0xFF008F92),
                start = Offset(c.x - r, c.y - r),
                end = Offset(c.x + r, c.y + r),
            ),
            r,
            c,
        )
        // The overlay's microphone, on its 24-unit grid; 1.45 dp per unit for a 43 dp radius.
        val u = r / 43f * 1.45f

        fun x(v: Float) = c.x + (v - 12f) * u

        fun y(v: Float) = c.y + (v - 12f) * u
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round)
        drawRoundRect(Color.White, Offset(x(8.5f), y(2.5f)), Size(7f * u, 12f * u), CornerRadius(3.5f * u))
        drawArc(Color.White, 0f, 180f, false, Offset(x(5f), y(4f)), Size(14f * u, 14f * u), style = stroke)
        drawLine(Color.White, Offset(x(12f), y(18f)), Offset(x(12f), y(21.5f)), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(x(8.5f), y(21.5f)), Offset(x(15.5f), y(21.5f)), stroke.width, StrokeCap.Round)
    }
}

/**
 * Size slider for the floating button. Smooth, no steps; the original size sits in the middle.
 * While it moves, the real button shows over the screen at that size (see [FabPreview]).
 */
@Composable
internal fun FabSizeRow(
    scale: Float,
    onScale: (Float) -> Unit,
    blue: Boolean = false,
) {
    var value by remember { mutableFloatStateOf(scale) }
    LaunchedEffect(scale) { value = scale }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.fab_size), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        FabPreview.scale.value = it
                    },
                    onValueChangeFinished = {
                        onScale(value)
                        FabPreview.scale.value = null
                    },
                    valueRange = SettingsRepository.FAB_SCALE_MIN..SettingsRepository.FAB_SCALE_MAX,
                )
                Row {
                    Text(
                        stringResource(R.string.fab_size_smaller),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.fab_size_bigger),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(modifier = Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                FabIcon(diameter = 44f * value, blue = blue)
            }
        }
    }
}

/** Button colour: the site's green or turquoise blue, each shown as the button itself. */
@Composable
internal fun FabColorRow(
    blue: Boolean,
    onBlue: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.fab_color), style = MaterialTheme.typography.bodyLarge)
        Row(modifier = Modifier.padding(top = 8.dp)) {
            for ((isBlue, label) in listOf(false to R.string.fab_color_green, true to R.string.fab_color_blue)) {
                val selected = isBlue == blue
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 20.dp).clickable { onBlue(isBlue) },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                        if (selected) {
                            val ring = MaterialTheme.colorScheme.primary
                            Canvas(modifier = Modifier.size(52.dp)) {
                                drawCircle(ring, size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(width = 2.5.dp.toPx()))
                            }
                        }
                        FabIcon(diameter = 40f, blue = isBlue)
                    }
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Hide in apps": which apps, opening the list. */
@Composable
internal fun FabHiddenAppsRow(
    names: List<String>,
    onOpen: () -> Unit,
) {
    val summary =
        when {
            names.isEmpty() -> stringResource(R.string.fab_hidden_none)
            names.size <= 2 -> names.joinToString(", ")
            else -> stringResource(R.string.fab_hidden_more, names.take(2).joinToString(", "), names.size - 2)
        }
    ListItem(
        headlineContent = { Text(stringResource(R.string.fab_hidden_apps)) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen).padding(start = 40.dp),
    )
}
