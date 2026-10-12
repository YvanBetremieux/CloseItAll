package io.github.yvanbetremieux.closeitall.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdviceTest {
    private val heavy = UidStats(uid = 10150, mah = 505.0, wakelockMs = 2_400_000, alarmWakeups = 312, gpsMs = 2_820_000, backgroundMs = 12_000_000)

    @Test fun heavyBackgroundAppGetsBothRecommendations() {
        val a = Advice.forApp(heavy, 10.0, restricted = false, backgroundLocation = true, isSystem = false)
        assertTrue(a.recommendRestrict)
        assertTrue(a.recommendRevokeLocation)
        assertTrue(a.summary.contains("312 réveils"))
    }

    @Test fun alreadyRestrictedIsNotRecommendedAgain() {
        val a = Advice.forApp(heavy, 10.0, restricted = true, backgroundLocation = false, isSystem = false)
        assertFalse(a.recommendRestrict)
        assertFalse(a.recommendRevokeLocation)
    }

    @Test fun systemAppsAreNeverRestricted() {
        val a = Advice.forApp(heavy, 10.0, restricted = false, backgroundLocation = true, isSystem = true)
        assertFalse(a.recommendRestrict)
        assertFalse(a.recommendRevokeLocation)
    }

    @Test fun quietAppHasNoRecommendation() {
        val a = Advice.forApp(UidStats(1, mah = 3.0), 0.05, restricted = false, backgroundLocation = false, isSystem = false)
        assertFalse(a.recommendRestrict)
        assertTrue(a.summary.startsWith("Rien d'anormal"))
    }
}

class AdviceBackgroundTest {
    @Test fun backgroundShareIsExplained() {
        val stats = UidStats(10150, mah = 505.0, backgroundMah = 405.0)
        val a = Advice.forApp(stats, 10.0, restricted = false, backgroundLocation = false, isSystem = false)
        assertTrue(a.summary, a.summary.startsWith("8 % de batterie consommés en arrière-plan"))
        assertTrue(a.recommendRestrict)
    }
}
