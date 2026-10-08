package ru.gigapisar.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.gigapisar.R

/**
 * What the accessibility service sees and does, asked before opening its settings: Google Play
 * requires this disclosure and an explicit "agree" for apps that are not accessibility tools.
 */
@Composable
internal fun AccessibilityDisclosure(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = { Text(stringResource(R.string.disclosure_text)) },
        confirmButton = { TextButton(onClick = onAgree) { Text(stringResource(R.string.disclosure_agree)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.disclosure_decline)) } },
    )
}
