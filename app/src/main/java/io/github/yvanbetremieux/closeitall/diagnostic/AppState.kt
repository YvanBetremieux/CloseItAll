package io.github.yvanbetremieux.closeitall.diagnostic

enum class BatteryMode(val label: String) {
    UNRESTRICTED("Non restreinte"),
    OPTIMIZED("Optimisée"),
    RESTRICTED("Restreinte"),
}

/** Parsing of the shell outputs that describe an app's background state. */
object AppStateParsing {
    private val packageName = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun isValidPackage(name: String): Boolean = packageName.matches(name)

    /**
     * Output of `for p in …; do echo "@@$p"; cmd appops get $p RUN_ANY_IN_BACKGROUND; done`.
     * Returns the packages whose mode is `ignore` (Android's "Restricted").
     */
    fun restrictedPackages(output: String): Set<String> {
        val restricted = mutableSetOf<String>()
        var current: String? = null
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("@@")) {
                current = t.removePrefix("@@")
            } else if (current != null && t.startsWith("RUN_ANY_IN_BACKGROUND") && t.contains(": ignore")) {
                restricted += current
            }
        }
        return restricted
    }

    /** `dumpsys deviceidle whitelist` → packages the user exempted ("Non restreinte"). */
    fun userWhitelist(output: String): Set<String> = output.lineSequence()
        .map { it.trim().split(',') }
        .filter { it.size >= 2 && it[0] == "user" }
        .map { it[1] }
        .toSet()

    fun mode(pkg: String, restricted: Set<String>, whitelist: Set<String>): BatteryMode = when (pkg) {
        in restricted -> BatteryMode.RESTRICTED
        in whitelist -> BatteryMode.UNRESTRICTED
        else -> BatteryMode.OPTIMIZED
    }
}
