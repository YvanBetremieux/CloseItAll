package io.github.yvanbetremieux.closeitall.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateParsingTest {
    @Test fun readsRestrictedFromAppops() {
        val out = """
            @@com.facebook.katana
            RUN_ANY_IN_BACKGROUND: ignore; time=+2d1h ago
            @@com.zhiliaoapp.musically
            No operations.
            @@com.snapchat.android
            RUN_ANY_IN_BACKGROUND: allow; rejectTime=+5m ago
        """.trimIndent()
        assertEquals(setOf("com.facebook.katana"), AppStateParsing.restrictedPackages(out))
    }

    @Test fun readsUserWhitelist() {
        val out = "system-excidle,com.android.shell,2000\nsystem,com.google.android.gms,10120\nuser,com.whatsapp,10200\n"
        assertEquals(setOf("com.whatsapp"), AppStateParsing.userWhitelist(out))
    }

    @Test fun modeResolution() {
        assertEquals(BatteryMode.RESTRICTED, AppStateParsing.mode("a.b", setOf("a.b"), setOf("a.b")))
        assertEquals(BatteryMode.UNRESTRICTED, AppStateParsing.mode("a.b", emptySet(), setOf("a.b")))
        assertEquals(BatteryMode.OPTIMIZED, AppStateParsing.mode("a.b", emptySet(), emptySet()))
    }

    @Test fun rejectsShellInjectionInPackageNames() {
        assertTrue(AppStateParsing.isValidPackage("com.facebook.katana"))
        assertFalse(AppStateParsing.isValidPackage("com.x; reboot"))
        assertFalse(AppStateParsing.isValidPackage("noDots"))
    }
}
