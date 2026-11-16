package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class ThemeHubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureFirebaseInitialized()
    }

    internal fun ensureFirebaseInitialized() {
        try {
            val defaultApp = FirebaseApp.initializeApp(this)
            if (defaultApp != null) {
                Log.i("ThemeHubApp", "FirebaseApp initialized from google-services resources")
                return
            }
        } catch (e: Exception) {
            Log.w("ThemeHubApp", "FirebaseApp.initializeApp(Context) warning/error: ${e.message}")
        }

        if (FirebaseApp.getApps(this).isNotEmpty()) {
            Log.i("ThemeHubApp", "FirebaseApp is already initialized")
            return
        }

        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:417666381540:android:04f18b94b1d7d8fa00def1")
                .setProjectId("theme-hub-baad8")
                .setApiKey("AIzaSyAz6N93Cx6GvGUe9MoZCIvRaRuRdE_RQtM")
                .setStorageBucket("theme-hub-baad8.firebasestorage.app")
                .build()

            FirebaseApp.initializeApp(this, options)
            Log.i("ThemeHubApp", "FirebaseApp initialized via explicit FirebaseOptions fallback")
        } catch (e: Exception) {
            Log.e("ThemeHubApp", "Fatal: Failed to initialize FirebaseApp", e)
        }
    }
}
