package io.github.yvanbetremieux.closeitall.diagnostic

import io.github.yvanbetremieux.closeitall.shell.Shell
import io.github.yvanbetremieux.closeitall.shell.ShellResult

/** One-tap fixes on another app, each with its inverse for "Annuler". */
class AppActions(private val shell: Shell) {

    suspend fun restrict(pkg: String): ShellResult = guarded(pkg) {
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore && { dumpsys deviceidle whitelist -$pkg >/dev/null 2>&1; true; }"
    }

    suspend fun unrestrict(pkg: String): ShellResult = guarded(pkg) {
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
    }

    suspend fun revokeBackgroundLocation(pkg: String): ShellResult = guarded(pkg) {
        "pm revoke $pkg $BACKGROUND_LOCATION"
    }

    suspend fun grantBackgroundLocation(pkg: String): ShellResult = guarded(pkg) {
        "pm grant $pkg $BACKGROUND_LOCATION"
    }

    suspend fun forceStop(pkg: String): ShellResult = guarded(pkg) { "am force-stop $pkg" }

    private suspend fun guarded(pkg: String, command: () -> String): ShellResult =
        if (AppStateParsing.isValidPackage(pkg)) shell.run(command())
        else ShellResult(-1, "", "Nom de paquet invalide : $pkg")

    private companion object {
        const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    }
}
