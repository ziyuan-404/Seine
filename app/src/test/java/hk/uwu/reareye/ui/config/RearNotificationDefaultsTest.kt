package hk.uwu.reareye.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearNotificationDefaultsTest {
    @Test
    fun defaultsIncludeExistingAndNewApps() {
        assertTrue(RearNotificationDefaults.includes(RearNotificationDefaults.APPS, "com.tencent.mm"))
        assertTrue(RearNotificationDefaults.includes(RearNotificationDefaults.APPS, "newly.installed.app"))
    }

    @Test
    fun savedEmptyAndCustomSelectionsRemainExplicit() {
        assertFalse(RearNotificationDefaults.includes(emptySet(), "com.tencent.mm"))
        val selected = setOf("com.android.soundrecorder")
        assertTrue(RearNotificationDefaults.includes(selected, "com.android.soundrecorder"))
        assertFalse(RearNotificationDefaults.includes(selected, "com.tencent.mm"))
    }
}
