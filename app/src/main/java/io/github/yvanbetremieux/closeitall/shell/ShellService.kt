package io.github.yvanbetremieux.closeitall.shell

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import io.github.yvanbetremieux.closeitall.IShellService
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Runs inside a Shizuku-spawned process with the `shell` UID. */
@Keep
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): Array<String> {
        val process = ProcessBuilder("sh", "-c", command).start()
        var err = ""
        val errReader = thread { err = process.errorStream.bufferedReader().readText() }
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        errReader.join()
        return arrayOf(code.toString(), out.take(MAX_CHARS), err.take(MAX_CHARS / 10))
    }

    override fun stream(command: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                runCatching {
                    val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                    process.inputStream.copyTo(out)
                    process.waitFor()
                }
            }
        }
        return read
    }

    private companion object {
        // Keeps a reply well under the 1 MB binder transaction limit (UTF-16).
        const val MAX_CHARS = 200_000
    }
}
