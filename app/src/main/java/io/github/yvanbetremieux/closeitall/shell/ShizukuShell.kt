package io.github.yvanbetremieux.closeitall.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.yvanbetremieux.closeitall.BuildConfig
import io.github.yvanbetremieux.closeitall.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** [Shell] backed by a Shizuku user service running as the `shell` user. */
class ShizukuShell(context: Context, private val state: ShizukuState) : Shell {
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val mutex = Mutex()
    @Volatile private var service: IShellService? = null

    override suspend fun run(command: String): ShellResult = withContext(Dispatchers.IO) {
        val s = service() ?: return@withContext ShellResult.UNAVAILABLE
        try {
            val r = s.run(command)
            ShellResult(r[0].toIntOrNull() ?: -1, r[1], r[2]).also {
                if (!it.ok) Log.w(TAG, "`$command` → ${it.code}: ${it.err.take(300)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "`$command` failed", e)
            service = null
            ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun read(command: String): String? = withContext(Dispatchers.IO) {
        val s = service() ?: return@withContext null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(s.stream(command)).use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            Log.w(TAG, "`$command` stream failed", e)
            service = null
            null
        }
    }

    private suspend fun service(): IShellService? = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() } ?: bind()
    }

    private suspend fun bind(): IShellService? {
        state.refresh()
        if (!state.isReady) return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
                            service = s
                            if (cont.isActive) cont.resume(s)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    try {
                        Shizuku.bindUserService(args, connection)
                    } catch (e: Exception) {
                        Log.w(TAG, "bindUserService failed", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CloseItAll"
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
