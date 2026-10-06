package com.starglean.app.core.online

import android.content.Context
import com.starglean.app.BuildConfig
import com.starglean.app.core.account.*
import com.starglean.app.core.network.*
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.starglean.app.core.storage.baseStorageContext

/** Process-wide identity coordinator; construction performs no network request. */
class OnlineRuntime private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val auth = SupabaseAuthClient(
        UrlConnectionAuthTransport(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
        AndroidKeystoreSessionStore(context.applicationContext),
    )
    val appAccess = AppAccessController(auth, scope)
    val cloudEntryLinks = CloudEntryLinkStore(context.applicationContext)
    val nearby = NearbyRepository(SupabaseNearbyClient(
        URL(BuildConfig.SUPABASE_URL), BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        AccessTokenProvider {
            when (val result = auth.currentSession()) {
                is AuthOutcome.Success -> result.value?.accessToken
                is AuthOutcome.Failure -> null
            }
        },
        authRejectionHandler = AuthRejectionHandler { token -> auth.onAuthRejected(token) },
    ))
    val reactions = ReactionNoticeController(nearby, cloudEntryLinks, auth, scope)
    val dailyReads = DailyReadsMemory()
    companion object {
        @Volatile private var instance: OnlineRuntime? = null
        fun get(context: Context): OnlineRuntime = instance ?: synchronized(this) {
            instance ?: OnlineRuntime(baseStorageContext(context)).also { instance = it }
        }
    }
}
