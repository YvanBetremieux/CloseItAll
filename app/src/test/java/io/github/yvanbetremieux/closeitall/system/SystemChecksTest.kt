package io.github.yvanbetremieux.closeitall.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemChecksTest {
    private fun check(id: String) = SystemChecks.all.first { it.id == id }

    @Test fun missingValueUsesAndroidDefault() {
        assertEquals(false, check("ble_scan").isOk(null))
        assertEquals(false, check("ble_scan").isOk("null"))
        assertEquals(true, check("aod").isOk(null))
    }

    @Test fun numericComparison() {
        assertEquals(true, check("smooth_display").isOk("60.0"))
        assertEquals(false, check("smooth_display").isOk("120.0"))
        assertEquals(true, check("battery_saver_trigger").isOk("25"))
        assertEquals(false, check("battery_saver_trigger").isOk("0"))
    }

    @Test fun unknownWithoutDefaultIsNull() {
        assertNull(check("smooth_display").isOk(null))
    }
}
