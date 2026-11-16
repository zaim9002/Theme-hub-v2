package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthErrorFormatterTest {

    @Test
    fun `default_web_client_id is resolved from google_services json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        assertTrue("default_web_client_id should exist", resId != 0)
        val clientId = context.getString(resId)
        assertTrue("clientId should end with .apps.googleusercontent.com", clientId.endsWith(".apps.googleusercontent.com"))
    }

    @Test
    fun `BuildConfig DEFAULT_WEB_CLIENT_ID fallback is valid`() {
        assertTrue(
            "BuildConfig.DEFAULT_WEB_CLIENT_ID should be non-empty OAuth client ID",
            BuildConfig.DEFAULT_WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com")
        )
    }

    @Test
    fun `formats ApiException 10 clearly`() {
        val formatted = AuthErrorFormatter.format(ApiException(Status(10, "DEVELOPER_ERROR")))
        assertTrue(formatted.contains("ApiException 10"))
    }

    @Test
    fun `formats ApiException 12500 clearly`() {
        val formatted = AuthErrorFormatter.format(ApiException(Status(12500, "Sign in failed")))
        assertTrue(formatted.contains("ApiException 12500"))
    }

    @Test
    fun `formats FirebaseFirestoreException permission denied clearly`() {
        val ex = FirebaseFirestoreException("Missing permissions", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        val formatted = AuthErrorFormatter.format(ex)
        assertTrue(formatted.contains("permission-denied"))
    }

    @Test
    fun `formats FirebaseAuthException error code clearly`() {
        val ex = FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "The supplied auth credential is incorrect.")
        val formatted = AuthErrorFormatter.format(ex)
        assertTrue(formatted.contains("auth/invalid-credential"))
    }
}
