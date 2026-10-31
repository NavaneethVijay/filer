package com.navaneeth.filer.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun versionComparison() {
        assertTrue(UpdateManager.isNewerVersion("1.0.4", "1.0.3"))
        assertTrue(UpdateManager.isNewerVersion("1.1.0", "1.0.3"))
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.0.3"))
        assertTrue(UpdateManager.isNewerVersion("1.0.3.1", "1.0.3"))

        assertFalse(UpdateManager.isNewerVersion("1.0.3", "1.0.3"))
        assertFalse(UpdateManager.isNewerVersion("1.0.2", "1.0.3"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.3"))
        assertFalse(UpdateManager.isNewerVersion("0.9.9", "1.0.3"))

        // With prefixes / suffixes
        assertTrue(UpdateManager.isNewerVersion("1.0.4-beta", "1.0.3"))
        assertFalse(UpdateManager.isNewerVersion("1.0.3-beta", "1.0.3"))
    }
}
