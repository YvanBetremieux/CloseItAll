package io.github.yvanbetremieux.closeitall.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCloserTest {

    @Test fun runningPackagesStripsProcessSuffixAndHeader() {
        val ps = """
            NAME
            init
            [kthreadd]
            com.facebook.katana
            com.facebook.katana:notification
            com.zhiliaoapp.musically:push
            /system/bin/surfaceflinger
        """.trimIndent()
        assertEquals(setOf("com.facebook.katana", "com.zhiliaoapp.musically"), AppCloser.runningPackages(ps))
    }

    @Test fun foregroundServicesAreAttributedToTheirPackage() {
        val out = """
              * ServiceRecord{1a2b3c u0 com.spotify.music/.playback.SpotifyService c:com.spotify.music}
                isForeground=true foregroundId=10 types=0x00000002
              * ServiceRecord{4d5e6f u0 com.facebook.katana/com.facebook.push.Service}
              * ServiceRecord{7a8b9c u0 com.waze/.NavigationService}
                isForeground=true foregroundId=3
              unknown line
        """.trimIndent()
        assertEquals(setOf("com.spotify.music", "com.waze"), AppCloser.foregroundServicePackages(out))
    }

    @Test fun foregroundServicesToleratesEmptyOrGarbage() {
        assertEquals(emptySet<String>(), AppCloser.foregroundServicePackages(""))
        assertEquals(emptySet<String>(), AppCloser.foregroundServicePackages("isForeground=true\nServiceRecord{"))
    }

    @Test fun resumedActivitiesAreProtected() {
        val out = """
              topResumedActivity=ActivityRecord{a1b2 u0 com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity t42}
            mResumedActivity: ActivityRecord{c3d4 u0 com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity t42}
            ResumedActivity: ActivityRecord{e5f6 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}
        """.trimIndent()
        assertEquals(setOf("com.zhiliaoapp.musically", "com.google.android.apps.nexuslauncher"), AppCloser.resumedPackages(out))
    }

    @Test fun selectsOnlyIdleUnprotectedUserApps() {
        val targets = AppCloser.selectTargets(
            running = setOf("com.facebook.katana", "com.whatsapp", "com.android.systemui", "com.spotify.music", "com.snapchat.android", "com.zhiliaoapp.musically"),
            userApps = setOf("com.facebook.katana", "com.whatsapp", "com.spotify.music", "com.snapchat.android", "com.zhiliaoapp.musically"),
            protected = setOf("com.whatsapp", "com.spotify.music"),
            recentlyUsed = setOf("com.snapchat.android"),
        )
        assertEquals(listOf("com.facebook.katana", "com.zhiliaoapp.musically"), targets)
    }

    @Test fun invalidNamesAreNeverTargeted() {
        val targets = AppCloser.selectTargets(setOf("com.x; reboot"), setOf("com.x; reboot"), emptySet(), emptySet())
        assertEquals(emptyList<String>(), targets)
    }
}
