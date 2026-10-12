package io.github.yvanbetremieux.closeitall.automation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Keeps the set of connected Bluetooth devices: seeded from profile proxies and GATT,
 * then updated by ACL broadcasts. Without BLUETOOTH_CONNECT the count is unknown (null).
 */
@SuppressLint("MissingPermission")
class BluetoothTracker(private val context: Context, private val onChange: (Int?) -> Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val connected = mutableSetOf<String>()

    val isEnabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun count(): Int? = if (hasPermission()) synchronized(connected) { connected.size } else null

    /** Reads currently connected devices; calls [onChange] as proxies answer. */
    fun seed() {
        val adapter = adapter
        if (!hasPermission() || adapter == null) {
            onChange(null)
            return
        }
        runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { add(it) } }
        val profiles = buildList {
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
        for (profile in profiles) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { add(it) } }
                        adapter.closeProfileProxy(p, proxy)
                        onChange(count())
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
        onChange(count())
    }

    fun onAcl(device: BluetoothDevice?, isConnected: Boolean) {
        val address = device?.address ?: return
        synchronized(connected) { if (isConnected) connected += address else connected -= address }
        onChange(count())
    }

    fun clear() {
        synchronized(connected) { connected.clear() }
        onChange(count())
    }

    private fun add(device: BluetoothDevice) {
        synchronized(connected) { connected += device.address }
    }
}
