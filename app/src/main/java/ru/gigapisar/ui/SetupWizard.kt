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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import ru.gigapisar.model.ModelManager

/**
 * First-run setup: until the app can work, the screen shows only the next step, one
 * big button each (microphone, accessibility service, model), so the model download
 * can no longer be missed at the bottom of a list. Steps tick off by themselves: the
 * screen re-checks every second, e.g. after returning from system settings.
 */
@Composable
internal fun SetupWizard(
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRequestMicrophone: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onModelInstalled: () -> Unit,
) {
    val step =
        when {
            !microphoneGranted -> 1
            !accessibilityEnabled -> 2
            else -> 3
        }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepBar(step)
        Text(
            text = LocalContext.current.getString(R.string.setup_step, step),
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
                StepBody(
                    title = R.string.setup_access_title,
                    text = R.string.setup_access_text,
                    button = R.string.setup_access_button,
                    onClick = onOpenAccessibilitySettings,
                    hint = R.string.setup_access_hint,
                )
            else -> ModelStep(onModelInstalled)
        }
    }
}

@Composable
private fun StepBar(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 1..3) {
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
    val modelManager = remember(context) { ModelManager(context) }
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = context.getString(R.string.setup_model_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = context.getString(R.string.setup_model_text), style = MaterialTheme.typography.bodyLarge)
        if (downloading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text(text = context.getString(R.string.downloading_model, progress), style = MaterialTheme.typography.bodyMedium)
        } else {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    downloading = true
                    progress = 0
                    error = null
                    scope.launch {
                        try {
                            modelManager.download { value -> progress = value }
                            if (!modelManager.isInstalled()) {
                                throw IllegalStateException(context.getString(R.string.model_verification_failed))
                            }
                            onModelInstalled()
                        } catch (e: Throwable) {
                            error =
                                if (e is java.net.UnknownHostException ||
                                    e is java.net.ConnectException ||
                                    e is java.net.SocketTimeoutException
                                ) {
                                    // A DNS or connection failure reads like a server problem; it is the phone's network.
                                    context.getString(R.string.download_no_network)
                                } else {
                                    e.message ?: e.javaClass.simpleName
                                }
                        } finally {
                            downloading = false
                        }
                    }
                },
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
