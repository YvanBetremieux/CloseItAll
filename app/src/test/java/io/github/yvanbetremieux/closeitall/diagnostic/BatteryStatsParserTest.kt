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

class EstimatedPowerParserTest {
    private val text = """
        Estimated power use (mAh):
          Capacity: 5050, Computed drain: 1801, actual drain: 900-1100
          Global
            screen: 300 apps: 300 duration: 5h 2m 3s 1ms
          UID u0a150: 505 fg: 100 bg: 380 fgs: 25 cached: 0 ( cpu=200 (bg:180) gps=40 )
          UID u0a151: 120
          UID 1000: 30.5 ( cpu=30 )
          UID weird: 12
    """.trimIndent()

    @Test fun parsesUidsWithBackgroundBreakdown() {
        val r = BatteryStatsParser.parseEstimatedPower(text)
        assertEquals(5050.0, r.capacityMah!!, 0.01)
        val fb = r.uids.getValue(10150)
        assertEquals(505.0, fb.mah, 0.01)
        assertEquals(405.0, fb.backgroundMah!!, 0.01)
        assertEquals(null, r.uids.getValue(10151).backgroundMah)
        assertEquals(30.5, r.uids.getValue(1000).mah, 0.01)
        assertEquals(3, r.uids.size)
    }

    @Test fun parsesSecondaryUserUid() {
        assertEquals(1_010_005, BatteryStatsParser.parseUid("u10a5"))
        assertEquals(null, BatteryStatsParser.parseUid("weird"))
    }

    @Test fun mergeKeepsCheckinDetailsAndTextPower() {
        val checkin = BatteryReport(null, 1000L, mapOf(10150 to UidStats(10150, mah = 1.0, alarmWakeups = 9)))
        val merged = BatteryStatsParser.merge(checkin, BatteryStatsParser.parseEstimatedPower(text))
        val fb = merged.uids.getValue(10150)
        assertEquals(505.0, fb.mah, 0.01)
        assertEquals(9, fb.alarmWakeups)
        assertEquals(1000L, merged.onBatteryMs)
    }
}
