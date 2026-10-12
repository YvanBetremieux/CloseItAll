package io.github.yvanbetremieux.closeitall.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import io.github.yvanbetremieux.closeitall.BuildConfig
import io.github.yvanbetremieux.closeitall.CloseItAllApp
import io.github.yvanbetremieux.closeitall.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Checks GitHub Releases and installs the attached APK. */
class UpdateManager(private val context: Context, private val prefs: Prefs) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Daily automatic check; installs right away when "Installer automatiquement" is on. */
    suspend fun checkIfDue() {
        val p = prefs.updates.first()
        if (!p.autoCheck || System.currentTimeMillis() - p.lastCheck < DAY_MS) return
        val release = check() ?: return
        if (p.autoInstall) install(release)
    }

    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatest()
            prefs.setLastCheck(System.currentTimeMillis())
            if (release.isUpdateFor(BuildConfig.VERSION_NAME)) {
                _state.value = UpdateState.Available(release)
                release
            } else {
                _state.value = UpdateState.UpToDate
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.value = UpdateState.Error("Vérification impossible : ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    suspend fun install(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        val asset = release.apk() ?: return@withContext
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            download(asset.url, apk)
            _state.value = UpdateState.Installing
            val app = context.applicationContext as CloseItAllApp
            // Silent install through Shizuku when available; the system installer otherwise.
            if (app.shizuku.isReady && apk.absolutePath.startsWith("/storage")) {
                val result = app.shell.run("pm install -r \"${apk.absolutePath}\"")
                if (result.ok) return@withContext
                Log.w(TAG, "Shizuku install failed: ${result.err}")
            }
            installWithSession(apk)
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            _state.value = UpdateState.Error("Installation impossible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun reportError(message: String) {
        _state.value = UpdateState.Error(message)
    }

    private fun fetchLatest(): ReleaseInfo {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.optJSONArray("assets")
            val list = (0 until (assets?.length() ?: 0)).map { i ->
                val a = assets!!.getJSONObject(i)
                ReleaseAsset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
            }
            return ReleaseInfo(json.optString("tag_name"), json.optString("body"), list)
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun installWithSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    private companion object {
        const val TAG = "CloseItAll"
        const val LATEST_URL = "https://api.github.com/repos/YvanBetremieux/CloseItAll/releases/latest"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
