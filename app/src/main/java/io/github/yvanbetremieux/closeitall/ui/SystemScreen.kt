package io.github.yvanbetremieux.closeitall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.shell.ShellResult
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus
import io.github.yvanbetremieux.closeitall.system.CheckState
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(app: CloseItAllApp, status: ShizukuStatus, snackbar: SnackbarHostState, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<CheckState>?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val loaded = app.systemSettings.load()
            failed = loaded == null
            checks = loaded
        }
    }

    fun apply(action: suspend () -> ShellResult, done: String) {
        scope.launch {
            val r = action()
            snackbar.showSnackbar(if (r.ok) done else "Échec : ${r.err.ifBlank { r.out }.take(100)}")
            reload()
        }
    }

    LaunchedEffect(status) { if (status == ShizukuStatus.READY) reload() }

    if (status != ShizukuStatus.READY) {
        ShizukuRequiredCard(openSettings)
        return
    }
    if (failed) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lecture des réglages impossible pour l'instant.")
            Button(onClick = ::reload) { Text("Réessayer") }
        }
        return
    }
    val list = checks ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Réglages système", style = MaterialTheme.typography.headlineSmall)
                Text("Ces réglages restent appliqués après un redémarrage.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = ::reload) { Text("Actualiser") }
            }
        }
        items(list, key = { it.check.id }) { state ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (icon, tint) = when {
                            state.ok == true -> Icons.Default.CheckCircle to Color(0xFF2E9D5B)
                            state.check.suggestionOnly -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
                            else -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                        }
                        Icon(icon, null, tint = tint)
                        Text(state.check.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(state.current ?: "?", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(state.check.explanation, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.ok != true) {
                            Button(onClick = { apply({ app.systemSettings.fix(state) }, "${state.check.label} : corrigé") }) {
                                Text(if (state.check.suggestionOnly) "Appliquer la suggestion" else "Corriger")
                            }
                        }
                        if (state.previous != null) {
                            OutlinedButton(onClick = { apply({ app.systemSettings.restore(state) }, "${state.check.label} : rétabli") }) {
                                Text("Rétablir")
                            }
                        }
                    }
                }
            }
        }
    }
}
