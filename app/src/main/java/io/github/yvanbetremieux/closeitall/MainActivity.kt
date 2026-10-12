package io.github.yvanbetremieux.closeitall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.yvanbetremieux.closeitall.automation.WatchService
import io.github.yvanbetremieux.closeitall.ui.AppScaffold
import io.github.yvanbetremieux.closeitall.ui.CloseItAllTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CloseItAllApp
        lifecycleScope.launch {
            WatchService.sync(this@MainActivity)
            app.updates.checkIfDue()
        }
        setContent { CloseItAllTheme { AppScaffold(app) } }
    }

    override fun onResume() {
        super.onResume()
        (application as CloseItAllApp).shizuku.refresh()
    }
}
