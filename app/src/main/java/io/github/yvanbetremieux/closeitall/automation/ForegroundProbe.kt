package io.github.yvanbetremieux.closeitall.automation

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Finds the app currently on screen from usage events (needs "Accès aux données d'utilisation"). */
class ForegroundProbe(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L
    private var lastPackage: String? = null

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Package of the last activity brought to the front, or the previous answer if nothing changed. */
    fun current(): String? {
        if (usm == null) return null
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 30 * 60_000 else lastQuery - 2_000
        lastQuery = now
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return lastPackage
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPackage = event.packageName
        }
        return lastPackage
    }
}
