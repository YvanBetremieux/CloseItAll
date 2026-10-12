package io.github.yvanbetremieux.closeitall.automation

/** Messaging apps kept by default: a force-stopped app receives no notification until reopened. */
val DEFAULT_CLOSE_EXCEPTIONS = setOf(
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.thoughtcrime.securesms",
    "org.telegram.messenger",
    "com.facebook.orca",
    "com.discord",
    "com.Slack",
    "com.microsoft.teams",
    "com.microsoft.office.outlook",
    "com.google.android.apps.messaging",
)

/** Pure parsing and selection for the "close background apps" rule. */
object AppCloser {
    private val packageName = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val serviceRecord = Regex("""ServiceRecord\{\S+ u\d+ ([A-Za-z0-9_.]+)/""")
    private val activityRecord = Regex("""ActivityRecord\{\S+ u\d+ ([A-Za-z0-9_.]+)/""")

    /** `dumpsys activity activities | grep -E 'ResumedActivity'` → apps on screen (split screen included). */
    fun resumedPackages(output: String): Set<String> =
        activityRecord.findAll(output).map { it.groupValues[1] }.toSet()

    /** `ps -A -o NAME` → packages with a running process (`com.app:remote` → `com.app`). */
    fun runningPackages(psOutput: String): Set<String> = psOutput.lineSequence()
        .map { it.trim().substringBefore(':') }
        .filter { packageName.matches(it) }
        .toSet()

    /**
     * `dumpsys activity services | grep -E 'ServiceRecord\{|isForeground=true'` → packages running
     * a foreground service (music, navigation, call, upload…).
     */
    fun foregroundServicePackages(output: String): Set<String> {
        val result = mutableSetOf<String>()
        var current: String? = null
        for (line in output.lineSequence()) {
            val record = serviceRecord.find(line)
            if (record != null) {
                current = record.groupValues[1]
            } else if (current != null && line.contains("isForeground=true")) {
                result += current
            }
        }
        return result
    }

    /** Running user apps that are neither protected nor used recently, sorted for stable logs. */
    fun selectTargets(
        running: Set<String>,
        userApps: Set<String>,
        protected: Set<String>,
        recentlyUsed: Set<String>,
    ): List<String> = running
        .filter { it in userApps && it !in protected && it !in recentlyUsed && packageName.matches(it) }
        .sorted()
}
