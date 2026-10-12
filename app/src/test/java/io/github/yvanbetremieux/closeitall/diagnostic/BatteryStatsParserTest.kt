package io.github.yvanbetremieux.closeitall.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BatteryStatsParserTest {
    private val text = javaClass.classLoader!!.getResource("batterystats_checkin.txt")!!.readText()
    private val report = BatteryStatsParser.parseCheckin(text)

    @Test fun readsCapacityAndBatteryTime() {
        assertEquals(5050.0, report.capacityMah!!, 0.01)
        assertEquals(28_800_000L, report.onBatteryMs)
    }

    @Test fun aggregatesFacebookFigures() {
        val fb = report.uids.getValue(10150)
        assertEquals(505.0, fb.mah, 0.01)
        assertEquals(2_400_000L, fb.wakelockMs)
        assertEquals(600, fb.wakelockCount)
        assertEquals(80, fb.jobCount)
        assertEquals(312, fb.alarmWakeups)
        assertEquals(2_820_000L, fb.gpsMs)
        assertEquals(3_600_000L, fb.topMs)
        assertEquals(12_000_000L, fb.backgroundMs)
        assertEquals(60_000_000L, fb.mobileBytes)
        assertEquals(10.0, report.percentOf(fb)!!, 0.01)
    }

    @Test fun ignoresUnknownMalformedAndOtherPeriodLines() {
        val other = report.uids.getValue(10151)
        assertEquals(120.0, other.mah, 0.01)
        assertEquals(60_000L, other.wakelockMs)
        assertEquals(2, report.uids.size)
    }

    @Test fun emptyOrForeignTextGivesEmptyReport() {
        val r = BatteryStatsParser.parseCheckin("Permission Denial: can't dump BatteryStats")
        assertEquals(0, r.uids.size)
        assertNotNull(r)
    }
}
