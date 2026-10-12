package io.github.yvanbetremieux.closeitall.diagnostic

/** Battery figures for one Linux UID since the last full charge. */
data class UidStats(
    val uid: Int,
    val mah: Double = 0.0,
    /** Part of [mah] spent while the app was not on screen, when Android reports it. */
    val backgroundMah: Double? = null,
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

    private val uidLine = Regex("""^\s*UID (\S+): ([\d.]+)(.*)$""")
    private val bgPart = Regex("""\b(bg|fgs|cached): ([\d.]+)""")
    private val capacityLine = Regex("""^\s*Capacity: ([\d.]+)""")

    /**
     * Parses the "Estimated power use" section of the text `dumpsys batterystats`
     * ("UID u0a150: 505 fg: 100 bg: 380 fgs: 25 cached: 0 ( cpu=… )").
     * Background = bg + fgs + cached when the breakdown is present.
     */
    fun parseEstimatedPower(text: String): BatteryReport {
        val uids = HashMap<Int, UidStats>()
        var capacity: Double? = null
        for (line in text.lineSequence()) {
            capacityLine.find(line)?.let { capacity = it.groupValues[1].toDoubleOrNull() }
            val m = uidLine.find(line) ?: continue
            val uid = parseUid(m.groupValues[1]) ?: continue
            val mah = m.groupValues[2].toDoubleOrNull() ?: continue
            val breakdown = m.groupValues[3].substringBefore('(')
            val parts = bgPart.findAll(breakdown).toList()
            val bg = if (parts.isEmpty()) null else parts.sumOf { it.groupValues[2].toDoubleOrNull() ?: 0.0 }
            val old = uids[uid]
            uids[uid] = UidStats(uid, mah = (old?.mah ?: 0.0) + mah, backgroundMah = bg?.plus(old?.backgroundMah ?: 0.0))
        }
        return BatteryReport(capacity, null, uids)
    }

    /** "u0a150" → 10150, "u10a5" → 1010005, "1000" → 1000. */
    fun parseUid(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val m = Regex("""^u(\d+)a(\d+)$""").find(raw) ?: return null
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }

    /** Detailed checkin figures, with mAh taken from the text report when available. */
    fun merge(checkin: BatteryReport, estimated: BatteryReport): BatteryReport {
        val merged = HashMap(checkin.uids)
        for ((uid, e) in estimated.uids) {
            val c = merged[uid] ?: UidStats(uid)
            merged[uid] = c.copy(mah = if (e.mah > 0) e.mah else c.mah, backgroundMah = e.backgroundMah ?: c.backgroundMah)
        }
        return BatteryReport(estimated.capacityMah ?: checkin.capacityMah, checkin.onBatteryMs, merged)
    }

    private fun List<String>.indexOf(value: String, startIndex: Int): Int {
        for (i in startIndex until size) if (this[i] == value) return i
        return -1
    }

    private fun List<String>.long(i: Int): Long? = getOrNull(i)?.toDoubleOrNull()?.toLong()
    private fun List<String>.int(i: Int): Int? = getOrNull(i)?.toDoubleOrNull()?.toInt()
    private fun List<String>.double(i: Int): Double? = getOrNull(i)?.toDoubleOrNull()
}
