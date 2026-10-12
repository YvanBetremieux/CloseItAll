package io.github.yvanbetremieux.closeitall.diagnostic

data class AppAdvice(
    val summary: String,
    val recommendRestrict: Boolean,
    val recommendRevokeLocation: Boolean,
)

object Advice {
    private const val WAKELOCK_MS = 20 * 60_000L
    private const val ALARMS = 100
    private const val JOBS = 50
    private const val GPS_MS = 10 * 60_000L
    private const val BACKGROUND_MS = 60 * 60_000L
    private const val HEAVY_PERCENT = 2.0
    private const val BG_PERCENT = 1.0

    fun forApp(
        stats: UidStats,
        percent: Double?,
        restricted: Boolean,
        backgroundLocation: Boolean,
        isSystem: Boolean,
    ): AppAdvice {
        val reasons = mutableListOf<String>()
        val bgPercent = stats.backgroundMah?.let { bg -> percent?.takeIf { stats.mah > 0 }?.times(bg / stats.mah) }
        if (bgPercent != null && bgPercent >= BG_PERCENT) reasons += "%.0f %% de batterie consommés en arrière-plan".format(bgPercent)
        if (stats.wakelockMs >= WAKELOCK_MS) reasons += "garde le téléphone éveillé ${Format.duration(stats.wakelockMs)}"
        if (stats.alarmWakeups >= ALARMS) reasons += "${stats.alarmWakeups} réveils par alarme"
        if (stats.jobCount >= JOBS) reasons += "${stats.jobCount} tâches en arrière-plan"
        if (stats.gpsMs >= GPS_MS) reasons += "${Format.duration(stats.gpsMs)} de GPS"
        if (stats.backgroundMs >= BACKGROUND_MS) reasons += "${Format.duration(stats.backgroundMs)} active en arrière-plan"
        val heavy = (percent ?: 0.0) >= HEAVY_PERCENT

        val restrict = !isSystem && !restricted && (reasons.isNotEmpty() || heavy)
        val revoke = !isSystem && backgroundLocation && stats.gpsMs >= GPS_MS

        val summary = buildString {
            if (reasons.isEmpty()) {
                append(if (heavy) "Consommation élevée, surtout pendant l'utilisation." else "Rien d'anormal en arrière-plan.")
            } else {
                append(reasons.joinToString(", ").replaceFirstChar { it.uppercase() })
                append('.')
            }
            val actions = listOfNotNull(
                "restreindre l'arrière-plan".takeIf { restrict },
                "retirer la localisation en arrière-plan".takeIf { revoke },
            )
            if (actions.isNotEmpty()) append(" Conseil : ").append(actions.joinToString(" et ")).append('.')
            if (restricted) append(" Déjà restreinte.")
        }
        return AppAdvice(summary, restrict, revoke)
    }
}

object Format {
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "${ms / 1000} s"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
        }
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f Go".format(b / 1e9)
        b >= 1_000_000 -> "%.0f Mo".format(b / 1e6)
        else -> "%.0f Ko".format(b / 1e3)
    }
}
