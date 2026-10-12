package io.github.yvanbetremieux.closeitall.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.automation.RuleConfig
import io.github.yvanbetremieux.closeitall.automation.WatchService
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus
import kotlinx.coroutines.launch

@Composable
fun AutomationScreen(app: CloseItAllApp, status: ShizukuStatus, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by app.prefs.rules.collectAsStateWithLifecycle(initialValue = RuleConfig())
    val log by app.actionLog.entries.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    fun update(change: (RuleConfig) -> RuleConfig) {
        scope.launch {
            app.prefs.updateRules(change)
            WatchService.sync(context)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (status != ShizukuStatus.READY) ShizukuRequiredCard(openSettings)
        Text("Automatismes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        if (!config.enabled) {
            Text(
                "CloseItAll est désactivée (onglet Réglages) : aucun automatisme ne s'exécute.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        RuleCard(
            title = "Couper le Bluetooth",
            subtitle = "Quand aucun appareil n'est connecté depuis ${config.btDelayMin} min. Il n'est jamais rallumé automatiquement.",
            checked = config.btEnabled,
            onChecked = { v -> update { it.copy(btEnabled = v) } },
            delay = config.btDelayMin,
            onDelay = { v -> update { it.copy(btDelayMin = v) } },
        )

        RuleCard(
            title = "Couper la localisation",
            subtitle = "Allumée dès qu'une app GPS s'ouvre, coupée ${config.locDelayMin} min après l'avoir quittée (sauf navigation en cours).",
            checked = config.locEnabled,
            onChecked = { v -> update { it.copy(locEnabled = v) } },
            delay = config.locDelayMin,
            onDelay = { v -> update { it.copy(locDelayMin = v) } },
        ) {
            Text("Apps GPS", style = MaterialTheme.typography.titleSmall)
            config.gpsApps.sorted().forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabel(context.packageManager, pkg), modifier = Modifier.weight(1f))
                    IconButton(onClick = { update { it.copy(gpsApps = it.gpsApps - pkg) } }) { Icon(Icons.Default.Close, "Retirer") }
                }
            }
            TextButton(onClick = { picking = true }) { Text("Ajouter une app…") }
        }

        SectionTitle("Journal")
        if (log.isEmpty()) {
            Text("Aucune action pour l'instant.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        log.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Text(" ", modifier = Modifier.padding(16.dp))
    }

    if (picking) {
        AppPicker(
            exclude = config.gpsApps,
            onDismiss = { picking = false },
            onPick = { pkg ->
                picking = false
                update { it.copy(gpsApps = it.gpsApps + pkg) }
            },
        )
    }
}

@Composable
private fun RuleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    delay: Int,
    onDelay: (Int) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var sliderValue by remember(delay) { mutableStateOf(delay.toFloat()) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = onChecked)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            if (checked) {
                Text("Délai : ${sliderValue.toInt()} min", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onDelay(sliderValue.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                extra()
            }
        }
    }
}

@Composable
private fun AppPicker(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val pm = LocalContext.current.packageManager
    var query by remember { mutableStateOf("") }
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Choisir une app GPS") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Rechercher") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps.filter { it.first !in exclude && it.second.contains(query, ignoreCase = true) }, key = { it.first }) { (pkg, label) ->
                        Text(label, modifier = Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

private fun appLabel(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
