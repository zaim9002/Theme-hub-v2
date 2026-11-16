package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeDownloadHelperTest {

    @Test
    fun `pauseDownload marks key as paused`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ThemeDownloadHelper(context)
        val key = "space_honor"
        assertFalse(helper.isPaused(key))
        helper.pauseDownload(key)
        assertTrue(helper.isPaused(key))
    }

    @Test
    fun `getThemeEnvironmentInfo returns valid json with brand and empty defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ThemeDownloadHelper(context)
        val info = helper.getThemeEnvironmentInfo("Honor", "space_honor")
        assertEquals("Honor", info.getString("brand"))
        assertFalse(info.getBoolean("hasSavedFile"))
        assertFalse(info.getBoolean("hasSafFolder"))
        assertEquals(0L, info.getLong("pendingBytes"))
    }

    @Test
    fun `buildInitialTreeHintUri targets Honor Themes folder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ThemeDownloadHelper(context)
        val hintUri = helper.buildInitialTreeHintUri("Honor")
        assertNotNull(hintUri)
        assertTrue(hintUri.toString().contains("Honor%2FThemes") || hintUri.toString().contains("Honor/Themes"))
    }

    @Test
    fun `findThemeManagerPackage returns null instead of fallback when uninstalled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ThemeDownloadHelper(context)
        assertNull(helper.findThemeManagerPackage("Honor"))
        assertNull(helper.findThemeManagerPackage("Huawei"))
    }

    @Test
    fun `getThemeEnvironmentInfo supports app_update brand cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ThemeDownloadHelper(context)
        val info = helper.getThemeEnvironmentInfo("app_update", "app_update_apk")
        assertEquals("app_update", info.getString("brand"))
        assertFalse(info.getBoolean("hasSavedFile"))
        assertEquals("", info.getString("copiedPath"))
    }
}
