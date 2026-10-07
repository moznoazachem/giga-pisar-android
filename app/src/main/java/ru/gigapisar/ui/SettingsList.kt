package ru.gigapisar.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteOff
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.SwipeUp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import ru.gigapisar.R
import ru.gigapisar.settings.AppLanguage
import ru.gigapisar.settings.InsertionMode

private const val SITE_URL = "https://gigapisar.github.io"
private const val SOURCE_URL = "https://github.com/moznoazachem/giga-pisar-android"
private const val DOWNLOAD_URL = "https://github.com/moznoazachem/giga-pisar-android/releases/latest"

/** The settings as one list in the style of the system Settings app. */
@Composable
internal fun SettingsList(
    insertionMode: InsertionMode,
    virtualButtonVisible: Boolean,
    volumeKeyEnabled: Boolean,
    vibrationEnabled: Boolean,
    noClipboard: Boolean = false,
    fabScale: Float = 1f,
    fabGestures: Boolean = true,
    fabBlue: Boolean = false,
    hiddenAppNames: List<String> = emptyList(),
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    onInsertionMode: (InsertionMode) -> Unit,
    onVirtualButton: (Boolean) -> Unit,
    onVolumeKey: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onNoClipboard: (Boolean) -> Unit = {},
    onFabScale: (Float) -> Unit = {},
    onFabGestures: (Boolean) -> Unit = {},
    onFabBlue: (Boolean) -> Unit = {},
    onOpenHiddenApps: () -> Unit = {},
    onRequestMicrophone: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    brainSection: @Composable () -> Unit = {},
    onLanguageChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    Column {
        SectionTitle(R.string.section_dictation)
        SwitchRow(
            icon = Icons.Outlined.RadioButtonChecked,
            title = R.string.floating_button,
            subtitle =
                if (insertionMode == InsertionMode.TEXT_FIELD) {
                    R.string.floating_button_text_field
                } else {
                    R.string.floating_button_always
                },
            checked = virtualButtonVisible,
            onChange = onVirtualButton,
        )
        if (virtualButtonVisible) {
            FabSizeRow(scale = fabScale, onScale = onFabScale, blue = fabBlue)
            FabColorRow(blue = fabBlue, onBlue = onFabBlue)
            SwitchRow(
                icon = Icons.Outlined.SwipeUp,
                title = R.string.fab_gestures,
                subtitle = R.string.fab_gestures_hint,
                checked = fabGestures,
                onChange = onFabGestures,
            )
            FabHiddenAppsRow(names = hiddenAppNames, onOpen = onOpenHiddenApps)
        }
        SwitchRow(
            icon = Icons.AutoMirrored.Outlined.VolumeDown,
            title = R.string.volume_key,
            subtitle = R.string.volume_key_hint,
            checked = volumeKeyEnabled,
            onChange = onVolumeKey,
        )
        SwitchRow(
            icon = Icons.Outlined.Vibration,
            title = R.string.vibration,
            subtitle = R.string.vibration_hint,
            checked = vibrationEnabled,
            onChange = onVibration,
        )

        SectionTitle(R.string.section_insertion)
        RadioRow(
            icon = Icons.Outlined.EditNote,
            title = R.string.mode_text_field,
            subtitle = R.string.mode_text_field_hint,
            selected = insertionMode == InsertionMode.TEXT_FIELD,
            onClick = { onInsertionMode(InsertionMode.TEXT_FIELD) },
        )
        RadioRow(
            icon = Icons.Outlined.ContentPaste,
            title = R.string.mode_clipboard,
            subtitle = R.string.mode_clipboard_hint,
            selected = insertionMode == InsertionMode.CLIPBOARD,
            onClick = { onInsertionMode(InsertionMode.CLIPBOARD) },
        )

        if (insertionMode == InsertionMode.TEXT_FIELD) {
            SwitchRow(
                icon = Icons.Outlined.ContentPasteOff,
                title = R.string.no_clipboard,
                subtitle = R.string.no_clipboard_hint,
                checked = noClipboard,
                onChange = onNoClipboard,
            )
        }

        brainSection()

        SectionTitle(R.string.section_permissions)
        StatusRow(
            icon = Icons.Outlined.Mic,
            title = R.string.microphone,
            ok = microphoneGranted,
            okText = R.string.permission_granted,
            badText = R.string.permission_denied,
            onClick = if (microphoneGranted) null else onRequestMicrophone,
        )
        StatusRow(
            icon = Icons.Outlined.AccessibilityNew,
            title = R.string.accessibility_service,
            ok = accessibilityEnabled,
            okText = R.string.service_enabled,
            badText = R.string.service_disabled,
            onClick = onOpenAccessibilitySettings,
            chevron = true,
        )

        SectionTitle(R.string.section_recognition)
        InfoRow(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.model_name),
            subtitle = stringResource(R.string.model_installed_hint),
        )

        SectionTitle(R.string.section_about)
        LanguageRow(onLanguageChanged)
        InfoRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.about_site),
            subtitle = SITE_URL.removePrefix("https://"),
            onClick = { openUrl(context, SITE_URL) },
        )
        InfoRow(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.about_source),
            subtitle = stringResource(R.string.about_source_hint),
            onClick = { openUrl(context, SOURCE_URL) },
        )
        InfoRow(
            icon = Icons.Outlined.Share,
            title = stringResource(R.string.about_share),
            onClick = { share(context) },
        )
    }
}

@Composable
internal fun SectionTitle(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // Lines up with the row titles: 16 dp padding + 24 dp icon + 16 dp gap.
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: Int,
    subtitle: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(subtitle)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun RadioRow(
    icon: ImageVector,
    title: Int,
    subtitle: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(subtitle)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: Int,
    ok: Boolean,
    okText: Int,
    badText: Int,
    onClick: (() -> Unit)?,
    chevron: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Text(
                text = stringResource(if (ok) okText else badText),
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent =
            if (chevron) {
                { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }
            } else {
                null
            },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

private fun openUrl(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        // No browser: nothing to open it with.
    }
}

private fun share(context: Context) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_text, DOWNLOAD_URL))
        }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: Exception) {
        // Nothing can share text: nothing to do.
    }
}

/** Interface language: the phone's own, Russian or English. A change restarts the screen in the new language. */
@Composable
private fun LanguageRow(onChanged: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = AppLanguage.get(context)
    val options =
        listOf(
            AppLanguage.SYSTEM to R.string.language_system,
            AppLanguage.RUSSIAN to R.string.language_ru,
            AppLanguage.ENGLISH to R.string.language_en,
        )
    ListItem(
        headlineContent = { Text(stringResource(R.string.language)) },
        supportingContent = { Text(stringResource(options.first { it.first == current }.second)) },
        leadingContent = { Icon(Icons.Outlined.Translate, contentDescription = null) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    for ((code, label) in options) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = code == current, role = Role.RadioButton) {
                                        open = false
                                        if (code != current) {
                                            AppLanguage.set(context, code)
                                            onChanged()
                                        }
                                    }.padding(vertical = 10.dp),
                        ) {
                            RadioButton(selected = code == current, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.language_close)) } },
        )
    }
}
