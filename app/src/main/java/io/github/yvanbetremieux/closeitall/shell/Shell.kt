package io.github.yvanbetremieux.closeitall.shell

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0

    companion object {
        val UNAVAILABLE = ShellResult(-1, "", "Shizuku indisponible")
    }
}

/** Every privileged system access goes through this interface. */
interface Shell {
    /** Short command: exit code and outputs. */
    suspend fun run(command: String): ShellResult

    /** Command with a potentially large output; null when Shizuku is unavailable. */
    suspend fun read(command: String): String?
}
