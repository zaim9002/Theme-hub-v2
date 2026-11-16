package com.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MainActivityLaunchTest {

    @Test
    fun `MainActivity launches cleanly without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertNotNull(activity.resolveWebClientId())
                val versionJson = org.json.JSONObject(AndroidWebAppInterface(activity).getAppVersionInfo())
                org.junit.Assert.assertEquals(BuildConfig.VERSION_NAME, versionJson.getString("versionName"))
                org.junit.Assert.assertEquals(BuildConfig.VERSION_CODE, versionJson.getInt("versionCode"))
                val localeJson = org.json.JSONObject(AndroidWebAppInterface(activity).getDeviceLocaleInfo())
                org.junit.Assert.assertTrue(localeJson.getString("language").isNotBlank())
                org.junit.Assert.assertTrue(localeJson.getString("languageTag").isNotBlank())
            }
        }
    }
}
