package io.github.yvanbetremieux.closeitall

import android.app.Application
import io.github.yvanbetremieux.closeitall.automation.ActionLog
import io.github.yvanbetremieux.closeitall.diagnostic.AppActions
import io.github.yvanbetremieux.closeitall.diagnostic.DiagnosticRepository
import io.github.yvanbetremieux.closeitall.settings.Prefs
import io.github.yvanbetremieux.closeitall.shell.Shell
import io.github.yvanbetremieux.closeitall.shell.ShizukuShell
import io.github.yvanbetremieux.closeitall.shell.ShizukuState
import io.github.yvanbetremieux.closeitall.system.SystemSettingsRepository
import io.github.yvanbetremieux.closeitall.update.UpdateManager

/** Hand-wired dependencies, shared by the UI and the watch service. */
class CloseItAllApp : Application() {
    lateinit var shizuku: ShizukuState
        private set
    lateinit var shell: Shell
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var actionLog: ActionLog
        private set
    val diagnostics by lazy { DiagnosticRepository(this, shell) }
    val appActions by lazy { AppActions(shell) }
    val systemSettings by lazy { SystemSettingsRepository(shell, prefs) }
    val updates by lazy { UpdateManager(this, prefs) }

    override fun onCreate() {
        super.onCreate()
        shizuku = ShizukuState(this)
        shell = ShizukuShell(this, shizuku)
        prefs = Prefs(this)
        actionLog = ActionLog(this)
    }
}
