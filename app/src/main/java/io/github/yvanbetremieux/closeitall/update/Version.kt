package io.github.yvanbetremieux.closeitall.update

/** Numeric dotted version ("v1.2.3", "1.2.3-beta" → [1, 2, 3]). */
data class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        fun parse(raw: String): Version? {
            val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            if (core.isEmpty()) return null
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            return if (parts.size in 1..4) Version(parts) else null
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long = 0)

data class ReleaseInfo(val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    val version: Version? get() = Version.parse(tag)

    fun apk(): ReleaseAsset? = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /** True only when the tag is a newer version than [current] and an APK is attached. */
    fun isUpdateFor(current: String): Boolean {
        val remote = version ?: return false
        val local = Version.parse(current) ?: return false
        return apk() != null && remote > local
    }
}
