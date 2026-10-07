package com.starglean.app.core.online

import android.content.Context
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.core.storage.baseStorageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.staticCompositionLocalOf

val LocalConstellationSync = staticCompositionLocalOf<ConstellationCloudController?> { null }

/** Account-scoped local store with process-global auth/network and account-qualified cloud metadata. */
class ConstellationCloudRuntime(context: Context) {
    private val online = OnlineRuntime.get(context)
    private val base = baseStorageContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = ConstellationCloudController(
        online.nearby,
        ConstellationStore(context),
        online.cloudEntryLinks,
        ConstellationCloudPreferenceStore(base),
        scope,
        online.auth,
    )
    fun close() { controller.setActive(false, null); scope.cancel() }
}
