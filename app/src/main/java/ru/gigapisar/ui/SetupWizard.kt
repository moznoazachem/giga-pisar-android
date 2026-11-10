package ru.gigapisar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.gigapisar.R
import ru.gigapisar.model.ModelDownload
import ru.gigapisar.model.ModelManager

/**
 * First-run setup: until the app can work, the screen shows only the next step, one
 * big button each (microphone, the phone maker's own switches, accessibility service,
 * model), so the model download can no longer be missed at the bottom of a list. Steps
 * tick off by themselves: the screen re-checks every second, e.g. after returning from
 * system settings.
 */
@Composable
internal fun SetupWizard(
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    oemSetupDone: Boolean,
    onRequestMicrophone: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOemSetupDone: () -> Unit,
    onModelInstalled: () -> Unit,
) {
    val oem = remember { detectOem() }
    // Xiaomi, Huawei and friends need their own switches first: on those phones the
    // accessibility switch snaps back until the maker stops blocking the app.
    val oemStep = needsOemSetup(oem) && !oemSetupDone
    val total = if (needsOemSetup(oem)) 4 else 3
    val step =
        when {
            !microphoneGranted -> 1
            oemStep -> 2
            !accessibilityEnabled -> if (needsOemSetup(oem)) 3 else 2
            else -> total
        }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepBar(step, total)
        Text(
            text = LocalContext.current.getString(R.string.setup_step, step, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        when (step) {
            1 ->
                StepBody(
                    title = R.string.setup_mic_title,
                    text = R.string.setup_mic_text,
                    button = R.string.setup_mic_button,
                    onClick = onRequestMicrophone,
                )
            2 ->
                if (oemStep) {
                    OemStep(oem, onOemSetupDone)
                } else {
                    AccessibilityStep(onOpenAccessibilitySettings)
                }
            3 ->
                if (needsOemSetup(oem)) {
                    AccessibilityStep(onOpenAccessibilitySettings)
                } else {
                    ModelStep(onModelInstalled)
                }
            else -> ModelStep(onModelInstalled)
        }
    }
}

@Composable
private fun AccessibilityStep(onOpenAccessibilitySettings: () -> Unit) {
    StepBody(
        title = R.string.setup_access_title,
        text = R.string.setup_access_text,
        button = R.string.setup_access_button,
        onClick = onOpenAccessibilitySettings,
        hint = R.string.setup_access_hint,
    )
}

/**
 * The card for the phone maker's own gate. It says which shell was recognised and what exactly
 * it blocks, and gives two ways in: the app's info page, where Android 13+ hides "restricted
 * settings", and the maker's autostart list.
 */
@Composable
private fun OemStep(
    oem: Oem,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val text =
        when (oem) {
            Oem.XIAOMI -> R.string.setup_oem_text_xiaomi
            Oem.HUAWEI -> R.string.setup_oem_text_huawei
            Oem.SAMSUNG -> R.string.setup_oem_text_samsung
            Oem.OPPO -> R.string.setup_oem_text_oppo
            Oem.VIVO -> R.string.setup_oem_text_vivo
            Oem.OTHER -> R.string.setup_oem_text_other
        }
    val autostart = remember { autostartIntent(context) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = context.getString(R.string.setup_oem_title, oem.label),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(text = context.getString(text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { startIntent(context, appInfoIntent(context)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(context.getString(R.string.setup_oem_button_app_info))
        }
        autostart?.let {
            OutlinedButton(
                onClick = { startIntent(context, it) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                // Samsung has no autostart list: device care puts the app to sleep instead.
                Text(
                    context.getString(
                        if (oem == Oem.SAMSUNG) {
                            R.string.setup_oem_button_battery
                        } else {
                            R.string.setup_oem_button_autostart
                        },
                    ),
                )
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(context.getString(R.string.setup_oem_next))
        }
        Text(
            text = context.getString(R.string.setup_oem_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepBar(
    step: Int,
    total: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 1..total) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (i <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
            )
        }
    }
}

@Composable
private fun StepBody(
    title: Int,
    text: Int,
    button: Int,
    onClick: () -> Unit,
    hint: Int? = null,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = context.getString(title), style = MaterialTheme.typography.headlineSmall)
        Text(text = context.getString(text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(context.getString(button))
        }
        hint?.let {
            Text(
                text = context.getString(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelStep(onModelInstalled: () -> Unit) {
    val context = LocalContext.current
    // The download lives in ModelDownload, so turning the phone does not cancel it.
    val state by ModelDownload.state.collectAsState()
    LaunchedEffect(state) {
        if (state is ModelDownload.State.Done) onModelInstalled()
    }
    val running = state as? ModelDownload.State.Running
    val error = (state as? ModelDownload.State.Failed)?.reason

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = context.getString(R.string.setup_model_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = context.getString(R.string.setup_model_text), style = MaterialTheme.typography.bodyLarge)
        if (running != null) {
            LinearProgressIndicator(
                progress = { running.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text(text = context.getString(R.string.downloading_model, running.percent), style = MaterialTheme.typography.bodyMedium)
        } else {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { ModelDownload.start(context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(context.getString(if (error == null) R.string.setup_model_button else R.string.setup_model_retry))
            }
        }
        error?.let {
            Text(text = context.getString(R.string.download_error, it), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Shown on top of the settings once everything is ready, with a field to try dictation right away. */
@Composable
internal fun ReadyCard(
    volumeKeyEnabled: Boolean,
    buttonEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var sample by remember { mutableStateOf("") }
    val hint =
        when {
            volumeKeyEnabled -> R.string.ready_text
            buttonEnabled -> R.string.ready_text_button
            else -> R.string.ready_text_nothing
        }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = context.getString(R.string.ready_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = context.getString(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextField(
                value = sample,
                onValueChange = { sample = it },
                placeholder = { Text(context.getString(R.string.ready_try_here)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
            )
        }
    }
}
