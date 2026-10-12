package io.github.yvanbetremieux.closeitall.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val config = RuleConfig(btEnabled = true, locEnabled = true)
    private val maps = "com.google.android.apps.maps"

    /** Feeds events in order; returns the final state and every command emitted. */
    private fun run(
        vararg steps: Pair<Long, Event>,
        cfg: RuleConfig = config,
        start: RuleState = RuleState(),
    ): Pair<RuleState, List<Command>> {
        var state = start
        val all = mutableListOf<Command>()
        for ((t, e) in steps) {
            val (s, c) = RuleEngine.onEvent(cfg, state, e, t)
            state = s
            all += c
        }
        return state to all
    }

    private fun Long.min() = this * MINUTE

    @Test fun bluetoothOffAfterDelayWithoutDevices() {
        val (state, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.BluetoothPower(true),
            4L.min() to Event.Tick,
            5L.min() to Event.Tick,
        )
        assertEquals(1, cmds.count { it == Command.DisableBluetooth })
        assertFalse(state.btOn)
        assertNull(state.btIdleSince)
    }

    @Test fun btReconnectCancelsTimer() {
        val (state, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.BluetoothPower(true),
            2L.min() to Event.BluetoothDevices(1),
            6L.min() to Event.Tick,
        )
        assertFalse(cmds.contains(Command.DisableBluetooth))
        assertNull(state.nextDeadline(config))
    }

    @Test fun deviceDisconnectRestartsTimer() {
        val (_, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.BluetoothPower(true),
            1L.min() to Event.BluetoothDevices(1),
            10L.min() to Event.BluetoothDevices(0),
            14L.min() to Event.Tick,
        )
        assertFalse(cmds.contains(Command.DisableBluetooth))
        val (_, later) = run(
            0L to Event.Shizuku(true),
            0L to Event.BluetoothPower(true),
            1L.min() to Event.BluetoothDevices(1),
            10L.min() to Event.BluetoothDevices(0),
            15L.min() to Event.Tick,
        )
        assertTrue(later.contains(Command.DisableBluetooth))
    }

    @Test fun gpsAppInForegroundTurnsLocationOn() {
        val (state, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.LocationPower(false),
            1L.min() to Event.Foreground(maps),
            2L.min() to Event.Foreground(maps),
        )
        assertEquals(1, cmds.count { it == Command.EnableLocation })
        assertTrue(state.locOn)
    }

    @Test fun locationOffAfterLeavingGpsApp() {
        val (state, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.LocationPower(true),
            0L to Event.Foreground(maps),
            1L.min() to Event.Foreground("com.android.chrome"),
            11L.min() to Event.Tick,
        )
        assertTrue(cmds.contains(Command.CheckGpsService(config.gpsApps)))
        val (after, more) = RuleEngine.onEvent(config, state, Event.GpsServiceRunning(false), 11L.min())
        assertTrue(more.contains(Command.DisableLocation))
        assertFalse(after.locOn)
    }

    @Test fun gpsServiceRunningPostpones() {
        val (state, _) = run(
            0L to Event.Shizuku(true),
            0L to Event.LocationPower(true),
            0L to Event.Screen(false),
            10L.min() to Event.Tick,
        )
        assertTrue(state.awaitingGpsCheck)
        val (after, cmds) = RuleEngine.onEvent(config, state, Event.GpsServiceRunning(true), 10L.min())
        assertFalse(cmds.contains(Command.DisableLocation))
        assertTrue(after.locOn)
        assertEquals(20L.min(), after.nextDeadline(config))
    }

    @Test fun noShizukuNoCommands() {
        val (_, cmds) = run(
            0L to Event.Shizuku(false),
            0L to Event.BluetoothPower(true),
            0L to Event.LocationPower(true),
            1L.min() to Event.Foreground(maps),
            30L.min() to Event.Tick,
        )
        assertTrue(cmds.none { it !is Command.Log })
    }

    @Test fun shizukuLossLogsPause() {
        val (_, cmds) = run(
            0L to Event.Shizuku(true),
            1L.min() to Event.Shizuku(false),
        )
        assertTrue(cmds.any { it is Command.Log && it.message.contains("pause") })
    }

    @Test fun disabledAppDoesNothingAndHasNoDeadline() {
        val off = config.copy(enabled = false)
        val (state, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.BluetoothPower(true),
            0L to Event.LocationPower(true),
            60L.min() to Event.Tick,
            cfg = off,
        )
        assertTrue(cmds.isEmpty())
        assertNull(state.nextDeadline(off))
    }

    @Test fun ruleDisabledIndividually() {
        val btOnly = config.copy(locEnabled = false)
        val (_, cmds) = run(
            0L to Event.Shizuku(true),
            0L to Event.LocationPower(true),
            0L to Event.BluetoothPower(true),
            1L.min() to Event.BluetoothDevices(2),
            60L.min() to Event.Tick,
            cfg = btOnly,
        )
        assertTrue(cmds.none { it is Command.CheckGpsService || it == Command.DisableLocation || it == Command.DisableBluetooth })
    }
}
