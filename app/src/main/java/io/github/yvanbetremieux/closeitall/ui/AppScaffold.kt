package io.github.yvanbetremieux.closeitall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Diagnostic", Icons.Default.Search),
    Tab("Système", Icons.Default.Build),
    Tab("Automatismes", Icons.Default.Refresh),
    Tab("Réglages", Icons.Default.Settings),
)

const val TAB_SETTINGS = 3

@Composable
fun AppScaffold(app: CloseItAllApp) {
    val status by app.shizuku.status.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(if (status == ShizukuStatus.READY) 0 else TAB_SETTINGS) }
    val snackbar = remember { SnackbarHostState() }
    val openSettings = { selected = TAB_SETTINGS }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            if (index == TAB_SETTINGS && status != ShizukuStatus.READY) {
                                BadgedBox(badge = { Badge { Icon(Icons.Default.Warning, null) } }) { Icon(tab.icon, null) }
                            } else {
                                Icon(tab.icon, null)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> DiagnosticScreen(app, status, snackbar, openSettings)
                1 -> SystemScreen(app, status, snackbar, openSettings)
                2 -> AutomationScreen(app, status, openSettings)
                else -> SettingsScreen(app, status)
            }
        }
    }
}
