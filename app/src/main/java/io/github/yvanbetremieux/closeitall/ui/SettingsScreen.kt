package io.github.yvanbetremieux.closeitall.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yvanbetremieux.closeitall.BuildConfig
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.automation.ForegroundProbe
import io.github.yvanbetremieux.closeitall.automation.RuleConfig
import io.github.yvanbetremieux.closeitall.automation.WatchService
import io.github.yvanbetremieux.closeitall.settings.UpdatePrefs
import io.github.yvanbetremieux.closeitall.shell.ShizukuState
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus
import io.github.yvanbetremieux.closeitall.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(app: CloseItAllApp, status: ShizukuStatus) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by app.prefs.rules.collectAsStateWithLifecycle(initialValue = RuleConfig())
    val updatePrefs by app.prefs.updates.collectAsStateWithLifecycle(initialValue = UpdatePrefs(true, false, 0))
    val update by app.updates.state.collectAsStateWithLifecycle()
    // Bumped every second so permission rows re-read their state.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            app.shizuku.refresh()
            tick++
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        scope.launch { WatchService.sync(context) }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Réglages", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))

        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Activer CloseItAll", style = MaterialTheme.typography.titleMedium)
                    Text("Désactivée, l'app n'exécute aucun automatisme.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = config.enabled, onCheckedChange = { v ->
                    scope.launch {
                        app.prefs.updateRules { it.copy(enabled = v) }
                        WatchService.sync(context)
                    }
                })
            }
        }

        SectionTitle("Autorisations")
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        run {
            PermissionRow(
                title = "Shizuku",
                required = true,
                purpose = "Lire la consommation par app, restreindre les apps, couper Bluetooth et localisation.",
                granted = status == ShizukuStatus.READY,
                action = when (status) {
                    ShizukuStatus.NOT_INSTALLED -> "Installer Shizuku…" to { openShizukuStore(context) }
                    ShizukuStatus.NOT_RUNNING -> "Démarrer Shizuku…" to { openShizuku(context) }
                    ShizukuStatus.NO_PERMISSION -> "Autoriser…" to {
                        if (app.shizuku.permanentlyDenied()) openShizuku(context) else app.shizuku.requestPermission()
                    }
                    ShizukuStatus.READY -> null
                },
                help = when (status) {
                    ShizukuStatus.NOT_INSTALLED -> "Installe Shizuku, puis reviens ici."
                    ShizukuStatus.NOT_RUNNING -> "Dans Shizuku : « Démarrer via le débogage sans fil » (Wi-Fi requis), suis l'appairage la première fois."
                    else -> null
                },
            )
            val autostart = shizukuCanAutostart(context)
            PermissionRow(
                title = "Démarrage automatique de Shizuku",
                required = false,
                purpose = "Shizuku redémarre seul après un redémarrage du téléphone (en Wi-Fi).",
                granted = autostart,
                action = if (status == ShizukuStatus.READY && !autostart) {
                    "Configurer…" to {
                        scope.launch {
                            val r = app.shell.run("pm grant ${ShizukuState.SHIZUKU_PACKAGE} android.permission.WRITE_SECURE_SETTINGS")
                            if (r.ok) openShizuku(context)
                            tick++
                        }
                        Unit
                    }
                } else {
                    null
                },
                help = if (autostart) "Vérifie que « Démarrer au démarrage » est activé dans les réglages de Shizuku."
                else if (status != ShizukuStatus.READY) "Disponible une fois Shizuku autorisé." else null,
            )
            PermissionRow(
                title = "Accès aux données d'utilisation",
                required = false,
                purpose = "Savoir quelles apps tu as utilisées récemment, pour fermer les autres et pour la règle localisation.",
                granted = ForegroundProbe(context).hasAccess(),
                action = "Autoriser…" to { open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionRow(
                    title = "Appareils à proximité",
                    required = false,
                    purpose = "Compter les appareils Bluetooth connectés, pour la règle Bluetooth.",
                    granted = granted(context, Manifest.permission.BLUETOOTH_CONNECT),
                    action = "Autoriser…" to { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    title = "Notifications",
                    required = false,
                    purpose = "Afficher l'état des automatismes et prévenir quand Shizuku s'arrête.",
                    granted = granted(context, Manifest.permission.POST_NOTIFICATIONS),
                    action = "Autoriser…" to { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }

        SectionTitle("Mises à jour")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Text(
                "Dernière vérification : " +
                    if (updatePrefs.lastCheck == 0L) "jamais" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatePrefs.lastCheck)),
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow("Rechercher automatiquement", updatePrefs.autoCheck) { v -> scope.launch { app.prefs.setAutoCheck(v) } }
            SwitchRow("Installer automatiquement (sans confirmation avec Shizuku)", updatePrefs.autoInstall) { v ->
                scope.launch { app.prefs.setAutoInstall(v) }
            }
            when (val u = update) {
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateState.UpToDate -> Text("CloseItAll est à jour.")
                is UpdateState.Available -> {
                    Text("Nouvelle version : ${u.release.tag}", style = MaterialTheme.typography.titleMedium)
                    if (u.release.notes.isNotBlank()) Text(u.release.notes.take(1_500), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch { app.updates.install(u.release) } }) { Text("Installer ${u.release.tag}") }
                }
                is UpdateState.Downloading -> {
                    Text("Téléchargement… ${u.percent} %")
                    LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                UpdateState.Installing -> Text("Installation…")
                is UpdateState.Error -> Text(u.message, color = MaterialTheme.colorScheme.error)
                UpdateState.Idle -> Unit
            }
            OutlinedButton(onClick = { scope.launch { app.updates.check() } }, enabled = update != UpdateState.Checking) {
                Text("Rechercher les mises à jour…")
            }
            Text(" ")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    required: Boolean,
    purpose: String,
    granted: Boolean,
    action: Pair<String, () -> Unit>?,
    help: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint = if (granted) Color(0xFF2E9D5B) else if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (required) "Obligatoire" else "Optionnelle", style = MaterialTheme.typography.labelSmall)
        }
        Text(purpose, style = MaterialTheme.typography.bodySmall)
        if (!granted && help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (granted && help != null && action == null) Text(help, style = MaterialTheme.typography.bodySmall)
        if (!granted && action != null) Button(onClick = action.second) { Text(action.first) }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun shizukuCanAutostart(context: Context): Boolean =
    context.packageManager.checkPermission(Manifest.permission.WRITE_SECURE_SETTINGS, ShizukuState.SHIZUKU_PACKAGE) ==
        PackageManager.PERMISSION_GRANTED

private fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(ShizukuState.SHIZUKU_PACKAGE)
    if (launch != null) open(context, launch) else openShizukuStore(context)
}

private fun openShizukuStore(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuState.SHIZUKU_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")))
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
