package io.github.yvanbetremieux.closeitall.system

import io.github.yvanbetremieux.closeitall.settings.Prefs
import io.github.yvanbetremieux.closeitall.shell.Shell
import io.github.yvanbetremieux.closeitall.shell.ShellResult

data class CheckState(val check: SystemCheck, val current: String?, val ok: Boolean?, val previous: String?)

class SystemSettingsRepository(private val shell: Shell, private val prefs: Prefs) {

    /** null when Shizuku is unavailable. */
    suspend fun load(): List<CheckState>? {
        val script = SystemChecks.all.joinToString("; ") { "echo \"@@${it.id}=$(settings get ${it.namespace.cli} ${it.key})\"" }
        val result = shell.run(script)
        if (!result.ok) return null
        val values = result.out.lineSequence()
            .filter { it.startsWith("@@") }
            .associate { it.removePrefix("@@").substringBefore('=') to it.substringAfter('=') }
        return SystemChecks.all.map { check ->
            val current = values[check.id]
            CheckState(check, check.effective(current), check.isOk(current), prefs.previousValue(check.id))
        }
    }

    /** Applies the recommended value and remembers the old one for [restore]. */
    suspend fun fix(state: CheckState): ShellResult {
        val result = put(state.check, state.check.recommended)
        if (result.ok && state.previous == null) prefs.setPreviousValue(state.check.id, state.current ?: "null")
        return result
    }

    suspend fun restore(state: CheckState): ShellResult {
        val previous = state.previous ?: return ShellResult(-1, "", "Aucune valeur précédente")
        val result = if (previous == "null") {
            shell.run("settings delete ${state.check.namespace.cli} ${state.check.key}")
        } else {
            put(state.check, previous)
        }
        if (result.ok) prefs.setPreviousValue(state.check.id, null)
        return result
    }

    private suspend fun put(check: SystemCheck, value: String): ShellResult {
        require(value.matches(Regex("^[0-9.]+$"))) { "Valeur inattendue : $value" }
        return shell.run("settings put ${check.namespace.cli} ${check.key} $value")
    }
}
