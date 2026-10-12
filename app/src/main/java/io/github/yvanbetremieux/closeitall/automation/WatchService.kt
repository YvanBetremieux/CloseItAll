package io.github.yvanbetremieux.closeitall.automation

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.MainActivity
import io.github.yvanbetremieux.closeitall.R
import io.github.yvanbetremieux.closeitall.shell.ShellResult
import io.github.yvanbetremieux.closeitall.shell.ShizukuState
import io.github.yvanbetremieux.closeitall.shell.ShizukuStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service running the automations. Event-driven: broadcasts for screen,
 * Bluetooth and location; an inexact alarm only while a timer is pending; foreground polling
 * only while the screen is on and the location rule is enabled.
 */
class WatchService : LifecycleService() {
    private val app get() = application as CloseItAllApp
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var config = RuleConfig()
    private var state = RuleState()
    private var pollJob: Job? = null
    private var alarmSet = false
    private lateinit var bluetooth: BluetoothTracker
    private lateinit var foreground: ForegroundProbe
    private val handler = Handler(Looper.getMainLooper())
    private val alarm = AlarmManager.OnAlarmListener {
        alarmSet = false
        events.trySend(Event.Tick)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> events.trySend(Event.Screen(true))
                Intent.ACTION_SCREEN_OFF -> events.trySend(Event.Screen(false))
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_ON) {
                        events.trySend(Event.BluetoothPower(true))
                        bluetooth.seed()
                    } else if (s == BluetoothAdapter.STATE_OFF) {
                        bluetooth.clear()
                        events.trySend(Event.BluetoothPower(false))
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    bluetooth.onAcl(device, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
                }
                LocationManager.MODE_CHANGED_ACTION -> events.trySend(Event.LocationPower(locationEnabled()))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground(paused = false)
        bluetooth = BluetoothTracker(this) { count -> events.trySend(Event.BluetoothDevices(count ?: UNKNOWN_DEVICES)) }
        foreground = ForegroundProbe(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        lifecycleScope.launch {
            config = app.prefs.currentRules()
            val power = getSystemService(PowerManager::class.java)
            events.trySend(Event.Screen(power.isInteractive))
            events.trySend(Event.LocationPower(locationEnabled()))
            events.trySend(Event.BluetoothPower(bluetooth.isEnabled))
            if (bluetooth.isEnabled) bluetooth.seed()
            launch { for (e in events) process(e) }
            launch {
                app.prefs.rules.collect {
                    config = it
                    if (!it.anyRule) stopSelf() else events.trySend(Event.ConfigChanged)
                }
            }
            launch {
                app.shizuku.status.collect { status ->
                    events.trySend(Event.Shizuku(status == ShizukuStatus.READY))
                    startInForeground(paused = status != ShizukuStatus.READY)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        app.shizuku.refresh()
        if (::bluetooth.isInitialized && bluetooth.isEnabled) bluetooth.seed()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        cancelAlarm()
        events.close()
        super.onDestroy()
    }

    private suspend fun process(event: Event) {
        val now = SystemClock.elapsedRealtime()
        val (next, commands) = RuleEngine.onEvent(config, state, event, now)
        state = next
        for (command in commands) execute(command)
        scheduleTick(now)
        updatePolling()
    }

    private suspend fun execute(command: Command) {
        val shell = app.shell
        when (command) {
            Command.DisableBluetooth ->
                if (!report("Bluetooth", shell.run("cmd bluetooth_manager disable 2>/dev/null || svc bluetooth disable"))) {
                    events.trySend(Event.BluetoothPower(bluetooth.isEnabled))
                }
            Command.EnableLocation, Command.DisableLocation -> {
                val on = command == Command.EnableLocation
                val mode = if (on) 3 else 0
                if (!report("Localisation", shell.run("cmd location set-location-enabled $on 2>/dev/null || settings put secure location_mode $mode"))) {
                    events.trySend(Event.LocationPower(locationEnabled()))
                }
            }
            is Command.CheckGpsService -> {
                val packages = command.packages.filter { it.matches(PACKAGE) }
                val script = packages.joinToString("; ") { "dumpsys activity services $it | grep -q 'isForeground=true' && echo $it" } + "; true"
                val result = shell.run(script)
                // When the check fails, keep location on rather than cutting an ongoing navigation.
                events.trySend(Event.GpsServiceRunning(!result.ok || result.out.isNotBlank()))
            }
            is Command.Log -> app.actionLog.add(command.message)
        }
    }

    /** Logs a failed command; returns whether it succeeded. */
    private fun report(what: String, result: ShellResult): Boolean {
        if (!result.ok) app.actionLog.add("Échec ($what) : ${result.err.ifBlank { result.out }.take(120)}")
        return result.ok
    }

    private fun scheduleTick(now: Long) {
        val deadline = state.nextDeadline(config)
        cancelAlarm()
        if (deadline == null) return
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, maxOf(deadline, now) + 1_000, "CloseItAll:tick", alarm, handler)
        alarmSet = true
    }

    private fun cancelAlarm() {
        if (alarmSet) getSystemService(AlarmManager::class.java).cancel(alarm)
        alarmSet = false
    }

    private fun updatePolling() {
        val shouldPoll = config.enabled && config.locEnabled && state.screenOn && foreground.hasAccess()
        if (shouldPoll && pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch {
                var last: String? = null
                while (isActive) {
                    val pkg = foreground.current()
                    if (pkg != last) {
                        last = pkg
                        events.trySend(Event.Foreground(pkg))
                    }
                    delay(POLL_MS)
                }
            }
        } else if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun locationEnabled(): Boolean =
        runCatching { getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)

    private fun startInForeground(paused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Automatismes", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Notification permanente du service d'automatismes"
                    setShowBadge(false)
                },
            )
        }
        val tap = if (paused) {
            packageManager.getLaunchIntentForPackage(ShizukuState.SHIZUKU_PACKAGE)
        } else {
            null
        } ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Automatismes en pause" else "Automatismes actifs")
            .setContentText(if (paused) "Shizuku est arrêté : appuie pour le relancer" else "Bluetooth et localisation surveillés")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w("CloseItAll", "startForeground failed", e)
            app.actionLog.add("Service arrêté par Android : rouvre CloseItAll pour relancer les automatismes")
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL = "watch"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 10_000L
        /** Without the Bluetooth permission we cannot count devices: assume one is connected. */
        private const val UNKNOWN_DEVICES = 1
        private val PACKAGE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

        /** Starts or stops the service to match the saved rules. */
        suspend fun sync(context: Context) {
            val app = context.applicationContext as CloseItAllApp
            val intent = Intent(context, WatchService::class.java)
            if (app.prefs.rules.first().anyRule) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w("CloseItAll", "Cannot start WatchService", it) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
