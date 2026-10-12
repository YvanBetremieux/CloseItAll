package io.github.yvanbetremieux.closeitall.diagnostic

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.yvanbetremieux.closeitall.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val isSystem: Boolean,
    val stats: UidStats,
    val percent: Double?,
    val mode: BatteryMode?,
    val backgroundLocation: Boolean,
    val advice: AppAdvice,
)

sealed interface DiagnosticResult {
    data object ShizukuMissing : DiagnosticResult
    data class NoData(val raw: String) : DiagnosticResult
    data class Ok(val apps: List<AppEntry>, val onBatteryMs: Long?, val capacityMah: Double?) : DiagnosticResult
}

class DiagnosticRepository(private val context: Context, private val shell: Shell) {
    private val pm: PackageManager = context.packageManager

    suspend fun load(): DiagnosticResult = withContext(Dispatchers.IO) {
        val checkinRaw = shell.read(CHECKIN) ?: return@withContext DiagnosticResult.ShizukuMissing
        val textRaw = shell.read(ESTIMATED).orEmpty()
        val report = BatteryStatsParser.merge(
            BatteryStatsParser.parseCheckin(checkinRaw),
            BatteryStatsParser.parseEstimatedPower(textRaw),
        )
        val relevant = report.uids.values.filter { it.mah >= MIN_MAH || it.wakelockMs >= 60_000 || it.alarmWakeups >= 20 }
        if (relevant.isEmpty()) return@withContext DiagnosticResult.NoData(checkinRaw.take(20_000) + "\n---\n" + textRaw.take(20_000))

        val packages = relevant.associate { it.uid to primaryPackage(it.uid) }
        val names = packages.values.filterNotNull().filter(AppStateParsing::isValidPackage)
        val restricted = if (names.isEmpty()) emptySet() else AppStateParsing.restrictedPackages(
            shell.run(names.joinToString("; ") { "echo @@$it; cmd appops get $it RUN_ANY_IN_BACKGROUND" }).out,
        )
        val whitelist = AppStateParsing.userWhitelist(shell.run("dumpsys deviceidle whitelist").out)

        val apps = relevant.map { stats ->
            val pkg = packages[stats.uid]
            val info = pkg?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
            val isSystem = stats.uid < FIRST_APP_UID || (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
            val mode = pkg?.let { AppStateParsing.mode(it, restricted, whitelist) }
            val bgLocation = pkg != null &&
                pm.checkPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, pkg) == PackageManager.PERMISSION_GRANTED
            val percent = report.percentOf(stats)
            AppEntry(
                uid = stats.uid,
                packageName = pkg,
                label = info?.let { pm.getApplicationLabel(it).toString() } ?: systemLabel(stats.uid, pkg),
                isSystem = isSystem,
                stats = stats,
                percent = percent,
                mode = mode,
                backgroundLocation = bgLocation,
                advice = Advice.forApp(stats, percent, mode == BatteryMode.RESTRICTED, bgLocation, isSystem),
            )
        }.sortedByDescending { it.stats.mah }
        DiagnosticResult.Ok(apps, report.onBatteryMs, report.capacityMah)
    }

    private fun primaryPackage(uid: Int): String? =
        if (uid < FIRST_APP_UID) null else pm.getPackagesForUid(uid)?.minOrNull()

    private fun systemLabel(uid: Int, pkg: String?): String = when (uid) {
        0 -> "Noyau Android"
        1000 -> "Système Android"
        1001 -> "Téléphonie"
        1002 -> "Bluetooth (système)"
        1010 -> "Wi-Fi (système)"
        1013 -> "Multimédia"
        else -> pkg ?: "Processus système ($uid)"
    }

    private companion object {
        const val FIRST_APP_UID = 10_000
        const val MIN_MAH = 1.0
        const val CHECKIN = "dumpsys batterystats -c --charged | grep -E '^9,[0-9]+,l,(bt|pws|pwi|wl|jb|wua|sr|st|nt),'"
        const val ESTIMATED = "dumpsys batterystats --charged | grep -E '^ *(Capacity: |UID [^ ]+: )'"
    }
}
