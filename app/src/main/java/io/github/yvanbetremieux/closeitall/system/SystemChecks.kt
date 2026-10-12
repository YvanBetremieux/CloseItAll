package io.github.yvanbetremieux.closeitall.system

enum class SettingsNamespace(val cli: String) { GLOBAL("global"), SECURE("secure"), SYSTEM("system") }

enum class Match { EQUALS, AT_LEAST }

/** One system setting with its battery-friendly value. */
data class SystemCheck(
    val id: String,
    val namespace: SettingsNamespace,
    val key: String,
    val recommended: String,
    val label: String,
    val explanation: String,
    val match: Match = Match.EQUALS,
    /** Value Android uses when the key was never written. */
    val defaultWhenMissing: String? = null,
    /** Shown as a suggestion only (comfort trade-off). */
    val suggestionOnly: Boolean = false,
) {
    fun effective(current: String?): String? =
        current?.trim()?.takeUnless { it.isEmpty() || it == "null" } ?: defaultWhenMissing

    /** null when the current value is unknown. */
    fun isOk(current: String?): Boolean? {
        val value = effective(current) ?: return null
        val a = value.toDoubleOrNull()
        val b = recommended.toDoubleOrNull()
        if (a != null && b != null) {
            return when (match) {
                Match.EQUALS -> a == b
                Match.AT_LEAST -> a >= b
            }
        }
        return value == recommended
    }
}

object SystemChecks {
    val all = listOf(
        SystemCheck(
            id = "ble_scan",
            namespace = SettingsNamespace.GLOBAL,
            key = "ble_scan_always_enabled",
            recommended = "0",
            label = "Recherche Bluetooth",
            explanation = "Le téléphone cherche des appareils Bluetooth même quand le Bluetooth est coupé.",
            defaultWhenMissing = "1",
        ),
        SystemCheck(
            id = "wifi_scan",
            namespace = SettingsNamespace.GLOBAL,
            key = "wifi_scan_always_enabled",
            recommended = "0",
            label = "Recherche Wi-Fi",
            explanation = "Le téléphone cherche des réseaux Wi-Fi même quand le Wi-Fi est coupé.",
            defaultWhenMissing = "1",
        ),
        SystemCheck(
            id = "aod",
            namespace = SettingsNamespace.SECURE,
            key = "doze_always_on",
            recommended = "0",
            label = "Écran toujours allumé",
            explanation = "L'heure et les notifications restent affichées écran éteint.",
            defaultWhenMissing = "0",
        ),
        SystemCheck(
            id = "adaptive_battery",
            namespace = SettingsNamespace.GLOBAL,
            key = "adaptive_battery_management_enabled",
            recommended = "1",
            label = "Batterie adaptative",
            explanation = "Android limite les apps que vous utilisez peu.",
            defaultWhenMissing = "1",
        ),
        SystemCheck(
            id = "battery_saver_trigger",
            namespace = SettingsNamespace.GLOBAL,
            key = "low_power_trigger_level",
            recommended = "20",
            label = "Économiseur de batterie automatique",
            explanation = "Active l'économiseur automatiquement à 20 %.",
            match = Match.AT_LEAST,
            defaultWhenMissing = "0",
        ),
        SystemCheck(
            id = "smooth_display",
            namespace = SettingsNamespace.SYSTEM,
            key = "peak_refresh_rate",
            recommended = "60",
            label = "Affichage fluide (120 Hz)",
            explanation = "Limiter à 60 Hz économise un peu, au prix d'un défilement moins fluide.",
            suggestionOnly = true,
        ),
    )
}
