package io.github.yvanbetremieux.closeitall.automation

val DEFAULT_GPS_APPS = setOf("com.google.android.apps.maps", "com.waze")

data class RuleConfig(
    val enabled: Boolean = true,
    val btEnabled: Boolean = false,
    val btDelayMin: Int = 5,
    val locEnabled: Boolean = false,
    val locDelayMin: Int = 10,
    val gpsApps: Set<String> = DEFAULT_GPS_APPS,
) {
    val anyRule: Boolean get() = enabled && (btEnabled || locEnabled)
}

data class RuleState(
    val shizuku: Boolean = false,
    val screenOn: Boolean = true,
    val btOn: Boolean = false,
    val btDevices: Int = 0,
    val btIdleSince: Long? = null,
    val locOn: Boolean = false,
    val foreground: String? = null,
    val locIdleSince: Long? = null,
    val awaitingGpsCheck: Boolean = false,
) {
    fun btDeadline(config: RuleConfig): Long? = btIdleSince?.plus(config.btDelayMin * MINUTE)

    fun locDeadline(config: RuleConfig): Long? =
        if (awaitingGpsCheck) null else locIdleSince?.plus(config.locDelayMin * MINUTE)

    /** Earliest moment a [Event.Tick] can change something, or null when nothing is pending. */
    fun nextDeadline(config: RuleConfig): Long? = listOfNotNull(btDeadline(config), locDeadline(config)).minOrNull()
}

sealed interface Event {
    data class Shizuku(val available: Boolean) : Event
    data class Screen(val on: Boolean) : Event
    data class BluetoothPower(val on: Boolean) : Event
    data class BluetoothDevices(val count: Int) : Event
    data class LocationPower(val on: Boolean) : Event
    data class Foreground(val packageName: String?) : Event
    data class GpsServiceRunning(val running: Boolean) : Event
    data object Tick : Event
    data object ConfigChanged : Event
}

sealed interface Command {
    data object DisableBluetooth : Command
    data object EnableLocation : Command
    data object DisableLocation : Command
    data class CheckGpsService(val packages: Set<String>) : Command
    data class Log(val message: String) : Command
}

internal const val MINUTE = 60_000L

/** Pure decision logic for the Bluetooth and location automations. */
object RuleEngine {

    fun onEvent(config: RuleConfig, state: RuleState, event: Event, now: Long): Pair<RuleState, List<Command>> {
        val commands = mutableListOf<Command>()
        val active = config.enabled && state.shizuku
        var s = when (event) {
            is Event.Shizuku -> state.copy(shizuku = event.available)
            is Event.Screen -> state.copy(screenOn = event.on, foreground = if (event.on) state.foreground else null)
            is Event.BluetoothPower -> state.copy(btOn = event.on, btDevices = if (event.on) state.btDevices else 0)
            is Event.BluetoothDevices -> state.copy(btDevices = event.count.coerceAtLeast(0))
            is Event.LocationPower -> state.copy(locOn = event.on)
            is Event.Foreground -> state.copy(foreground = event.packageName)
            is Event.GpsServiceRunning -> state
            Event.Tick, Event.ConfigChanged -> state
        }
        val isActive = config.enabled && s.shizuku

        // Turn location on as soon as a GPS app comes to the front.
        if (event is Event.Foreground && config.locEnabled && isActive &&
            event.packageName in config.gpsApps && !s.locOn
        ) {
            commands += Command.EnableLocation
            commands += Command.Log("Localisation activée (${event.packageName})")
            s = s.copy(locOn = true)
        }

        // Answer to a CheckGpsService request.
        if (event is Event.GpsServiceRunning && s.awaitingGpsCheck) {
            s = s.copy(awaitingGpsCheck = false)
            if (event.running) {
                s = s.copy(locIdleSince = now)
                commands += Command.Log("Localisation conservée : une app GPS navigue encore")
            } else if (isActive && config.locEnabled && s.locOn && s.foreground !in config.gpsApps) {
                commands += Command.DisableLocation
                commands += Command.Log("Localisation coupée : aucune app GPS depuis ${config.locDelayMin} min")
                s = s.copy(locOn = false, locIdleSince = null)
            }
        }

        s = updateTimers(config, s, now)

        if (isActive && config.btEnabled) {
            val deadline = s.btDeadline(config)
            if (deadline != null && now >= deadline) {
                commands += Command.DisableBluetooth
                commands += Command.Log("Bluetooth coupé : aucun appareil depuis ${config.btDelayMin} min")
                s = s.copy(btOn = false, btIdleSince = null)
            }
        }
        if (isActive && config.locEnabled) {
            val deadline = s.locDeadline(config)
            if (deadline != null && now >= deadline) {
                commands += Command.CheckGpsService(config.gpsApps)
                s = s.copy(awaitingGpsCheck = true)
            }
        }
        if (event is Event.Shizuku && active != isActive && !isActive && config.anyRule) {
            commands += Command.Log("Automatismes en pause : Shizuku indisponible")
        }
        return s to commands
    }

    private fun updateTimers(config: RuleConfig, s: RuleState, now: Long): RuleState {
        val btIdle = config.enabled && config.btEnabled && s.btOn && s.btDevices == 0
        val locIdle = config.enabled && config.locEnabled && s.locOn && s.foreground !in config.gpsApps
        return s.copy(
            btIdleSince = if (btIdle) s.btIdleSince ?: now else null,
            locIdleSince = if (locIdle) s.locIdleSince ?: now else null,
            awaitingGpsCheck = s.awaitingGpsCheck && locIdle,
        )
    }
}
