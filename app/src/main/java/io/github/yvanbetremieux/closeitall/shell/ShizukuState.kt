package io.github.yvanbetremieux.closeitall.shell

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/** Tracks whether Shizuku is installed, running and has granted us access. */
class ShizukuState(private val context: Context) {
    private val _status = MutableStateFlow(compute())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    val isReady: Boolean get() = _status.value == ShizukuStatus.READY

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun refresh() {
        _status.value = compute()
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /** True when the user permanently denied access (must be re-allowed in the Shizuku app). */
    fun permanentlyDenied(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun compute(): ShizukuStatus = when {
        !Shizuku.pingBinder() -> if (isInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        Shizuku.isPreV11() -> ShizukuStatus.NO_PERMISSION
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 42
    }
}
