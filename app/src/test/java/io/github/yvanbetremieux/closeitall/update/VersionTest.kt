package io.github.yvanbetremieux.closeitall.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun parsesAndCompares() {
        assertTrue(Version.parse("v1.10.0")!! > Version.parse("1.9.3")!!)
        assertEquals(0, Version.parse("1.2")!!.compareTo(Version.parse("1.2.0")!!))
        assertEquals(listOf(0, 0, 0), Version.parse("0.0.0-dev")!!.parts)
    }

    @Test fun nonSemverTagIsNull() {
        assertNull(Version.parse("nightly"))
        assertNull(Version.parse(""))
    }

    @Test fun updateNeedsNewerVersionAndApk() {
        val apk = ReleaseAsset("CloseItAll-v0.2.0.apk", "u")
        assertTrue(ReleaseInfo("v0.2.0", "", listOf(apk)).isUpdateFor("0.1.0"))
        assertFalse(ReleaseInfo("v0.2.0", "", emptyList()).isUpdateFor("0.1.0"))
        assertFalse(ReleaseInfo("v0.1.0", "", listOf(apk)).isUpdateFor("0.1.0"))
        assertFalse(ReleaseInfo("nightly", "", listOf(apk)).isUpdateFor("0.1.0"))
    }
}
