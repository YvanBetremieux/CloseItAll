package io.github.yvanbetremieux.closeitall.automation

import android.Manifest
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import io.github.yvanbetremieux.closeitall.shell.Shell
import io.github.yvanbetremieux.closeitall.shell.ShizukuState

/** Outcome of one sweep, for the action log. */
sealed interface SweepResult {
    data object NoUsageAccess : SweepResult
    data object ShellUnavailable : SweepResult
    data class Closed(val packages: List<String>, val failed: Boolean) : SweepResult
}

/**
 * Force-stops the user apps that have not been on screen for the configured delay, keeping
 * everything the phone needs (launcher, keyboard, watch, music, navigation, alarms…).
 */
class BackgroundCloser(
    private val context: Context,
    private val shell: Shell,
    private val probe: ForegroundProbe,
) {
    private val pm = context.packageManager

    suspend fun sweep(config: RuleConfig): SweepResult {
        if (!probe.hasAccess()) return SweepResult.NoUsageAccess
        val ps = shell.run("ps -A -o NAME")
        if (!ps.ok) return SweepResult.ShellUnavailable
        val running = AppCloser.runningPackages(ps.out)
        val userApps = running.filterTo(mutableSetOf(), ::isUserApp)
        if (userApps.isEmpty()) return SweepResult.Closed(emptyList(), false)

        val services = shell.read("dumpsys activity services | grep -E 'ServiceRecord\\{|isForeground=true'")
            ?: return SweepResult.ShellUnavailable
        val resumed = shell.run("dumpsys activity activities | grep -E 'ResumedActivity'")
        // Without knowing what is on screen, closing anything could kill the app in use.
        if (!resumed.ok) return SweepResult.ShellUnavailable
        val protected = buildSet {
            addAll(AppCloser.resumedPackages(resumed.out))
            add(context.packageName)
            add(ShizukuState.SHIZUKU_PACKAGE)
            addAll(config.closeExceptions)
            addAll(AppCloser.foregroundServicePackages(services))
            addAll(systemRolePackages())
            probe.current()?.let(::add)
            userApps.filterTo(this, ::usesAlarmClock)
        }
        val targets = AppCloser.selectTargets(running, userApps, protected, recentlyUsed(config.closeDelayMin))
        if (targets.isEmpty()) return SweepResult.Closed(emptyList(), false)
        val result = shell.run(targets.joinToString("; ") { "am force-stop $it" })
        return SweepResult.Closed(targets, !result.ok)
    }

    fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun isUserApp(pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    /** Alarm clock apps lose their alarms when force-stopped. */
    private fun usesAlarmClock(pkg: String): Boolean = runCatching {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions.orEmpty().contains(USE_EXACT_ALARM)
    }.getOrDefault(false)

    /** Packages playing a system role: launcher, keyboard, accessibility, notification listeners (watch)… */
    private suspend fun systemRolePackages(): Set<String> = buildSet {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(::add)
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
        runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.packageName }.getOrNull()?.let(::add)
        runCatching { context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins }.getOrNull()
            ?.forEach { add(it.packageName) }
        val settings = shell.run(
            "settings get secure default_input_method; settings get secure enabled_accessibility_services; " +
                "settings get secure enabled_notification_listeners",
        )
        if (settings.ok) {
            settings.out.split('\n', ':').map { it.trim().substringBefore('/') }.filter { it.contains('.') }.forEach(::add)
        }
    }

    /** Packages that had an activity on screen during the last [minutes]. */
    private fun recentlyUsed(minutes: Int): Set<String> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptySet()
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - minutes * MINUTE, now) }.getOrNull() ?: return emptySet()
        val used = mutableSetOf<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                used += event.packageName
            }
        }
        return used
    }

    private companion object {
        val USE_EXACT_ALARM: String = Manifest.permission.USE_EXACT_ALARM
    }
}
