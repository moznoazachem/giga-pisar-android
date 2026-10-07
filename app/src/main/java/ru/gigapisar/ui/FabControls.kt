package ru.gigapisar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.gigapisar.R
import ru.gigapisar.overlay.FabPreview
import ru.gigapisar.settings.SettingsRepository

/**
 * Size slider for the floating button. Smooth, no steps; the original size sits in the middle.
 * While it moves, the real button shows over the screen at that size (see [FabPreview]): no sample next to it.
 */
@Composable
internal fun FabSizeRow(
    scale: Float,
    onScale: (Float) -> Unit,
) {
    var value by remember { mutableFloatStateOf(scale) }
    LaunchedEffect(scale) { value = scale }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.fab_size), style = MaterialTheme.typography.bodyLarge)
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
