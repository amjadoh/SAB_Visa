package com.sabvisa.core

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun newerVersionUsesBuildCodeIncludingBeta() {
        val update=AppUpdate(3,"0.1.0-beta.2","v0.1.0-beta.2")
        assertTrue(update.newerThan(2))
        assertFalse(update.newerThan(3))
        assertFalse(update.newerThan(4))
        assertEquals("https://github.com/amjadoh/SAB_Visa/releases/tag/v0.1.0-beta.2",update.pageUrl)
    }
    @Test fun untrustedMetadataCannotChangeDownloadDestination() {
        listOf("https://example.com", "v1/../../settings", "v1?token=secret", "v1#fragment", "v1%2fother").forEach {
            assertThrows(IllegalArgumentException::class.java) { AppUpdate(3,"1.0",it) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate(0,"1.0","v1.0") }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate(3,"bad\nname","v1.0") }
    }
}
