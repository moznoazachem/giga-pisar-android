package ru.gigapisar.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gigapisar.R

/** An installed app that can be opened from the launcher. */
internal class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** Apps with a launcher icon, except Pisar itself, sorted by name. Blocking: call off the main thread. */
internal fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val iconPx = (40 * context.resources.displayMetrics.density).toInt()
    return pm
        .queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            LaunchableApp(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = runCatching { pm.getApplicationIcon(info).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull(),
            )
        }.sortedBy { it.label.lowercase() }
}

/** Labels of the given packages, for the summary under "Hide in apps". */
internal fun appLabels(
    context: Context,
    packages: Set<String>,
): List<String> =
    packages
        .mapNotNull { pkg ->
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
        }.sortedBy { it.lowercase() }

/**
 * Apps where the floating button stays hidden: the list of installed apps with checkboxes and
 * a search. The ones already chosen come first.
 */
@Composable
internal fun HiddenAppsScreen(
    hidden: Set<String>,
    onChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    // Chosen apps stay on top while the page is open, not jumping as boxes are ticked.
    val firstChosen = remember { hidden }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.fab_hidden_apps), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.fab_hidden_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.fab_hidden_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        val list = apps
        if (list == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        val shown =
            list
                .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                .sortedBy { if (it.packageName in firstChosen) 0 else 1 }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(shown, key = { it.packageName }) { app ->
                val checked = app.packageName in hidden
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = {
                        app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                            ?: Box(modifier = Modifier.size(40.dp))
                    },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier =
                        Modifier.toggleable(value = checked, role = Role.Checkbox) { on ->
                            onChange(if (on) hidden + app.packageName else hidden - app.packageName)
                        },
                )
            }
        }
    }
}
