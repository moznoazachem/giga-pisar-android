package ru.gigapisar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.gigapisar.R
import ru.gigapisar.update.UpdateInfo
import ru.gigapisar.update.Updates
import java.io.File

/**
 * "Version X is out" on top of the settings: what is new and one button. The button
 * downloads the APK and opens Android's installer; the first time Android asks to let
 * Pisar install apps, and the banner says so.
 */
@Composable
internal fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(Updates.pending(context)) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        // Back from the "install unknown apps" switch: carry on with the install.
        val file = downloaded
        if (needsPermission && file != null && Updates.canInstall(context)) {
            needsPermission = false
            Updates.install(context, file)
        }
        // Every time the screen comes back, a fresh look at the manifest: a version that was
        // pulled (0.2.13, blocked by Play Protect) must give way to the one that replaced it,
        // not sit here with a dead link until the service's next six-hourly check.
        if (Updates.due(context, Updates.RECHECK_ON_OPEN_MS)) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { Updates.check(context) }
                // A check that reached the manifest resets the clock; one that failed leaves the old offer.
                if (!Updates.due(context, Updates.RECHECK_ON_OPEN_MS)) info = fresh
            }
        }
        onPauseOrDispose {}
    }

    val update: UpdateInfo = info ?: return

    fun proceed(file: File) {
        if (Updates.canInstall(context)) {
            Updates.install(context, file)
        } else {
            needsPermission = true
            Updates.openInstallPermission(context)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                Text(stringResource(R.string.update_title, update.version), style = MaterialTheme.typography.titleMedium)
            }
            val notes = update.notes(context)
            if (notes.isNotEmpty()) Text(notes, style = MaterialTheme.typography.bodyMedium)
            val p = progress
            when {
                p != null -> {
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Text(stringResource(R.string.update_downloading, p), style = MaterialTheme.typography.bodySmall)
                }
                needsPermission ->
                    Text(stringResource(R.string.update_allow_install), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (p == null) {
                Button(
                    onClick = {
                        error = null
                        val file = downloaded
                        if (file != null && file.exists()) {
                            proceed(file)
                            return@Button
                        }
                        progress = 0
                        scope.launch {
                            try {
                                val f = withContext(Dispatchers.IO) { Updates.download(context, update) { progress = it } }
                                downloaded = f
                                progress = null
                                proceed(f)
                            } catch (e: Exception) {
                                progress = null
                                error = context.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
                                // The file may be gone because the release was replaced: look for the new one right away.
                                val fresh = withContext(Dispatchers.IO) { Updates.check(context) }
                                if (fresh != null && fresh.versionCode != update.versionCode) {
                                    info = fresh
                                    error = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (needsPermission) R.string.update_allow_button else R.string.update_button)) }
            }
        }
    }
}
