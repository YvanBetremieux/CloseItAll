package io.github.yvanbetremieux.closeitall.diagnostic

/** Battery figures for one Linux UID since the last full charge. */
data class UidStats(
    val uid: Int,
    val mah: Double = 0.0,
    val wakelockMs: Long = 0,
    val wakelockCount: Int = 0,
    val jobCount: Int = 0,
    val jobMs: Long = 0,
    val alarmWakeups: Int = 0,
    val gpsMs: Long = 0,
    val topMs: Long = 0,
    val backgroundMs: Long = 0,
    val mobileBytes: Long = 0,
    val wifiBytes: Long = 0,
)

data class BatteryReport(
    val capacityMah: Double?,
    val onBatteryMs: Long?,
    val uids: Map<Int, UidStats>,
) {
    fun percentOf(stats: UidStats): Double? = capacityMah?.takeIf { it > 0 }?.let { stats.mah / it * 100 }
}

/**
 * Parses `dumpsys batterystats --checkin`. Only "since last charge" (`l`) lines are used;
 * unknown sections and malformed lines are ignored so new Android versions degrade gracefully.
 */
object BatteryStatsParser {
    private const val GPS_SENSOR = "-10000"

    fun parseCheckin(text: String): BatteryReport {
        val uids = HashMap<Int, UidStats>()
        var capacity: Double? = null
        var onBattery: Long? = null

        fun update(uid: Int, change: (UidStats) -> UidStats) {
            uids[uid] = change(uids[uid] ?: UidStats(uid))
        }

        for (rawLine in text.lineSequence()) {
            val f = rawLine.trim().split(',')
            if (f.size < 5 || f[2] != "l") continue
            val uid = f[1].toIntOrNull() ?: continue
            try {
                when (f[3]) {
                    "bt" -> onBattery = f.long(5)
                    "pws" -> capacity = f.double(4)
                    "pwi" -> if (f[4] == "uid") {
                        val mah = f.double(5) ?: 0.0
                        update(uid) { it.copy(mah = it.mah + mah) }
                    }
                    "wl" -> {
                        val p = f.indexOf("p", startIndex = 5)
                        if (p > 0) {
                            val time = f.long(p - 1) ?: 0
                            val count = f.int(p + 1) ?: 0
                            update(uid) { it.copy(wakelockMs = it.wakelockMs + time, wakelockCount = it.wakelockCount + count) }
                        }
                    }
                    "jb" -> {
                        val time = f.long(5) ?: 0
                        val count = f.int(6) ?: 0
                        update(uid) { it.copy(jobMs = it.jobMs + time, jobCount = it.jobCount + count) }
                    }
                    "wua" -> {
                        val count = f.int(5) ?: 0
                        update(uid) { it.copy(alarmWakeups = it.alarmWakeups + count) }
                    }
                    "sr" -> if (f[4] == GPS_SENSOR) {
                        val time = f.long(5) ?: 0
                        update(uid) { it.copy(gpsMs = it.gpsMs + time) }
                    }
                    "st" -> {
                        // top, foreground service, foreground, background, top sleeping, heavy weight, cached
                        val top = f.long(4) ?: 0
                        val bg = (f.long(5) ?: 0) + (f.long(6) ?: 0) + (f.long(7) ?: 0)
                        update(uid) { it.copy(topMs = top, backgroundMs = bg) }
                    }
                    "nt" -> {
                        val mobile = (f.long(4) ?: 0) + (f.long(5) ?: 0)
                        val wifi = (f.long(6) ?: 0) + (f.long(7) ?: 0)
                        update(uid) { it.copy(mobileBytes = mobile, wifiBytes = wifi) }
                    }
                }
            } catch (_: RuntimeException) {
                // Malformed line: skip it.
            }
        }
        return BatteryReport(capacity, onBattery, uids)
    }

    private fun List<String>.indexOf(value: String, startIndex: Int): Int {
        for (i in startIndex until size) if (this[i] == value) return i
        return -1
    }

    private fun List<String>.long(i: Int): Long? = getOrNull(i)?.toDoubleOrNull()?.toLong()
    private fun List<String>.int(i: Int): Int? = getOrNull(i)?.toDoubleOrNull()?.toInt()
    private fun List<String>.double(i: Int): Double? = getOrNull(i)?.toDoubleOrNull()
}
