package io.github.yvanbetremieux.closeitall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.diagnostic.AppEntry
import io.github.yvanbetremieux.closeitall.diagnostic.BatteryMode
import io.github.yvanbetremieux.closeitall.diagnostic.DiagnosticResult
import io.github.yvanbetremieux.closeitall.diagnostic.Format
import io.github.yvanbetremieux.closeitall.shell.ShellResult
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticScreen(app: CloseItAllApp, status: ShizukuStatus, snackbar: SnackbarHostState, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var result by remember { mutableStateOf<DiagnosticResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var hideSystem by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            result = app.diagnostics.load()
            loading = false
        }
    }

    /** Runs an action, then offers "Annuler" with its inverse. */
    fun act(done: String, action: suspend () -> ShellResult, undo: (suspend () -> ShellResult)?) {
        scope.launch {
            val r = action()
            if (!r.ok) {
                snackbar.showSnackbar("Échec : ${r.err.ifBlank { r.out }.take(100)}")
                return@launch
            }
            reload()
            if (undo != null) {
                val choice = snackbar.showSnackbar(done, actionLabel = "Annuler", duration = SnackbarDuration.Long)
                if (choice == SnackbarResult.ActionPerformed) {
                    undo()
                    reload()
                }
            } else {
                snackbar.showSnackbar(done)
            }
        }
    }

    LaunchedEffect(status) { if (status == ShizukuStatus.READY && result !is DiagnosticResult.Ok) reload() }

    if (status != ShizukuStatus.READY) {
        Column {
            ShizukuRequiredCard(openSettings)
            TextButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Ouvrir l'écran Batterie d'Android") }
        }
        return
    }

    when (val r = result) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        DiagnosticResult.ShizukuMissing -> ShizukuRequiredCard(openSettings)
        is DiagnosticResult.NoData -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Données indisponibles", style = MaterialTheme.typography.titleMedium)
            Text("Android n'a pas renvoyé de consommation par app. Juste après une charge complète, c'est normal : réessaie dans une heure.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::reload) { Text("Réessayer") }
                OutlinedButton(onClick = { copy(context, r.raw) }) { Text("Copier la sortie brute") }
            }
        }
        is DiagnosticResult.Ok -> {
            val apps = r.apps.filter { !hideSystem || !it.isSystem }
            val maxMah = apps.maxOfOrNull { it.stats.mah }?.takeIf { it > 0 } ?: 1.0
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Consommation par app", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Depuis la dernière charge complète" +
                                (r.onBatteryMs?.let { " (${Format.duration(it)} sur batterie)" } ?: "") + ".",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = hideSystem, onClick = { hideSystem = !hideSystem }, label = { Text("Masquer le système") })
                            TextButton(onClick = ::reload, enabled = !loading) { Text(if (loading) "Analyse…" else "Actualiser") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                items(apps, key = { it.uid }) { entry ->
                    AppCard(
                        entry = entry,
                        fraction = (entry.stats.mah / maxMah).toFloat(),
                        expanded = expanded == entry.uid,
                        onToggle = { expanded = if (expanded == entry.uid) null else entry.uid },
                        onRestrict = { pkg ->
                            act("${entry.label} restreinte", { app.appActions.restrict(pkg) }, { app.appActions.unrestrict(pkg) })
                        },
                        onUnrestrict = { pkg ->
                            act("${entry.label} n'est plus restreinte", { app.appActions.unrestrict(pkg) }, { app.appActions.restrict(pkg) })
                        },
                        onRevokeLocation = { pkg ->
                            act(
                                "Localisation en arrière-plan retirée",
                                { app.appActions.revokeBackgroundLocation(pkg) },
                                { app.appActions.grantBackgroundLocation(pkg) },
                            )
                        },
                        onForceStop = { pkg -> act("${entry.label} arrêtée", { app.appActions.forceStop(pkg) }, null) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppCard(
    entry: AppEntry,
    fraction: Float,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRestrict: (String) -> Unit,
    onUnrestrict: (String) -> Unit,
    onRevokeLocation: (String) -> Unit,
    onForceStop: (String) -> Unit,
) {
    val s = entry.stats
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    entry.percent?.let { "%.1f %%".format(it) } ?: "%.0f mAh".format(s.mah),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            val bg = s.backgroundMah?.takeIf { s.mah > 0 }?.let { "dont ${(it / s.mah * 100).toInt()} % en arrière-plan · " } ?: ""
            Text(
                bg + (entry.mode?.label ?: "Système"),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.mode == BatteryMode.RESTRICTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.advice.recommendRestrict || entry.advice.recommendRevokeLocation) {
                Text("Conseil disponible", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (expanded) {
                Text(entry.advice.summary, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = {}, label = { Text("${"%.0f".format(s.mah)} mAh") })
                    if (s.topMs > 0) AssistChip(onClick = {}, label = { Text("Écran : ${Format.duration(s.topMs)}") })
                    if (s.backgroundMs > 0) AssistChip(onClick = {}, label = { Text("Arrière-plan : ${Format.duration(s.backgroundMs)}") })
                    if (s.wakelockMs > 0) AssistChip(onClick = {}, label = { Text("Éveil : ${Format.duration(s.wakelockMs)} (${s.wakelockCount}×)") })
                    if (s.alarmWakeups > 0) AssistChip(onClick = {}, label = { Text("Alarmes : ${s.alarmWakeups}") })
                    if (s.jobCount > 0) AssistChip(onClick = {}, label = { Text("Tâches : ${s.jobCount}") })
                    if (s.gpsMs > 0) AssistChip(onClick = {}, label = { Text("GPS : ${Format.duration(s.gpsMs)}") })
                    if (s.mobileBytes > 0) AssistChip(onClick = {}, label = { Text("Mobile : ${Format.bytes(s.mobileBytes)}") })
                    if (entry.backgroundLocation) AssistChip(onClick = {}, label = { Text("Localisation en arrière-plan") })
                }
                val pkg = entry.packageName
                if (pkg != null && !entry.isSystem) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.mode == BatteryMode.RESTRICTED) {
                            OutlinedButton(onClick = { onUnrestrict(pkg) }) { Text("Ne plus restreindre") }
                        } else {
                            Button(onClick = { onRestrict(pkg) }) { Text("Restreindre") }
                        }
                        if (entry.backgroundLocation) {
                            Button(onClick = { onRevokeLocation(pkg) }) { Text("Retirer la localisation AP") }
                        }
                        OutlinedButton(onClick = { onForceStop(pkg) }) { Text("Forcer l'arrêt") }
                    }
                } else {
                    Text("Composant système : aucune action proposée.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("batterystats", text))
}
