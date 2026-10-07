package com.starglean.app

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.model.AppDestination
import com.starglean.app.core.storage.AccountStorageContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    // The phone edition remains English. Persisted diary content and enum/record identifiers
    // are never translated.
    override fun attachBaseContext(newBase: Context) {
        val appLocale = Locale.ENGLISH
        Locale.setDefault(appLocale)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList(appLocale))
            setLayoutDirection(appLocale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val launch = if (BuildConfig.DEBUG && BuildConfig.LOCAL_DEVICE_EDITION) {
            val destination = when (intent.getStringExtra("start_destination")) {
                "discover" -> AppDestination.Discover
                "write" -> AppDestination.Write
                "constellation" -> AppDestination.Constellations
                else -> AppDestination.Planet
            }
            // Device tests may select a real phone tab without enabling any simulated content,
            // sensor input, weather, reaction, or persistence path.
            DemoLaunch(destination = destination)
        } else {
            DemoLaunch()
        }
        // Debug device tests only. Release builds always go through the login gate.
        val qaLibrary = if (BuildConfig.DEBUG && intent.getBooleanExtra(QA_SIGNED_IN_EXTRA, false)) {
            AccountStorageContext.create(this, QA_LIBRARY_ID)
        } else null

        setContent {
            StargleanTheme {
                if (qaLibrary != null) StargleanQaSignedInApp(launch, qaLibrary) else StargleanApp(launch)
            }
        }
    }

    companion object {
        const val QA_SIGNED_IN_EXTRA = "qa_signed_in"
        /** Fixed id for the disposable QA library; never a real account. */
        const val QA_LIBRARY_ID = "0a0a0a0a-0000-4000-8000-00000000a11a"
    }
}
