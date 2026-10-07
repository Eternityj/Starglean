package com.starglean.app

import android.content.Context
import android.content.Intent
import com.starglean.app.core.location.AppLocationConsent
import com.starglean.app.core.location.appLocationConsentVersion
import com.starglean.app.core.storage.AccountStorageContext
import org.junit.Assume.assumeTrue

/**
 * Opens the real signed-in screens over one disposable QA library, without signing in.
 * The entry exists only in debug builds, so release device-test runs skip these tests.
 */
internal object QaSignedIn {
    /** The same storage the screens use in QA mode. Seed and inspect test data through this. */
    fun library(context: Context): AccountStorageContext {
        assumeTrue("The signed-in QA entry exists only in debug builds", BuildConfig.DEBUG)
        return AccountStorageContext.create(context, MainActivity.QA_LIBRARY_ID)
    }

    fun intent(context: Context, destination: String? = null): Intent {
        // Answer the location welcome with "Not now" once, so it never covers the screen under test.
        val location = library(context).getSharedPreferences(
            "foreground_location_${MainActivity.QA_LIBRARY_ID}", Context.MODE_PRIVATE)
        if (!location.contains("decision")) location.edit()
            .putInt("version", appLocationConsentVersion())
            .putString("decision", AppLocationConsent.Declined.name).commit()
        return Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.QA_SIGNED_IN_EXTRA, true)
            .apply { if (destination != null) putExtra("start_destination", destination) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
