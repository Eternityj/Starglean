package com.starglean.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.starglean.app.core.online.ConstellationCloudRuntime
import com.starglean.app.core.online.LocalConstellationSync
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.model.AppDestination
import com.starglean.app.ui.components.StargleanBottomBar
import com.starglean.app.ui.screens.ConstellationsScreen
import com.starglean.app.ui.screens.DiscoverScreen
import com.starglean.app.ui.screens.PlanetScreen
import com.starglean.app.ui.screens.SettingsScreen
import com.starglean.app.ui.screens.WriteScreen
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.ui.screens.AccountLibraryContent
import com.starglean.app.ui.screens.AppEntryGate
import com.starglean.app.ui.screens.AppLocationGate

data class DemoLaunch(
    val destination: AppDestination = AppDestination.Planet,
    val discoverSuccess: Boolean = false,
    val discoverHug: Boolean = false,
    val discoverReaction: String? = null,
    val discoverWeather: String? = null,
    val constellationLight: Boolean = false,
    val cygnusPreview: Boolean = false,
    val constellationsPreview: Boolean = false,
    val writeWeather: String? = null,
    val writeLiveWeatherTest: Boolean = false,
    val writePreview: Boolean = false,
    val galleryTour: Boolean = false,
    val galleryPanel: String? = null,
    val galleryStar: Int? = null,
    val galleryPage: Int = 0,
    val memoryCompassDemo: Boolean = false,
)

@Composable
fun StargleanApp(launch: DemoLaunch) {
    AppEntryGate { AppLocationGate { StargleanMainApp(launch) } }
}

/**
 * Debug device tests only: the real signed-in screens over a disposable local library.
 * Skips the login gate without creating or saving any session, so online features stay off.
 */
@Composable
internal fun StargleanQaSignedInApp(launch: DemoLaunch, library: AccountStorageContext) {
    AccountLibraryContent(library) { AppLocationGate { StargleanMainApp(launch) } }
}

@Composable
private fun StargleanMainApp(launch: DemoLaunch) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val account by runtime.auth.accountIdentity.collectAsState()
    val constellationSync = remember(context, account?.userId) { ConstellationCloudRuntime(context) }
    DisposableEffect(constellationSync) { onDispose { constellationSync.close() } }
    DisposableEffect(constellationSync, lifecycleOwner, account) {
        fun update() = constellationSync.controller.setActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), account)
        update()
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); constellationSync.controller.setActive(false, null) }
    }
    val reactionNotices by runtime.reactions.state.collectAsState()
    val writingDraftState = rememberSaveableStateHolder()
    val galleryState = rememberSaveableStateHolder()
    var destination by rememberSaveable(launch.destination) { mutableStateOf(launch.destination) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var cygnusPreviewAvailable by remember(launch.cygnusPreview) {
        mutableStateOf(launch.cygnusPreview && launch.destination == AppDestination.Planet)
    }
    var constellationsPreviewAvailable by remember(launch.constellationsPreview) {
        mutableStateOf(launch.constellationsPreview && launch.destination == AppDestination.Planet)
    }
    DisposableEffect(lifecycleOwner, account) {
        fun update() = runtime.reactions.setActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), account,
        )
        update()
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runtime.reactions.setActive(false, null)
        }
    }

    CompositionLocalProvider(LocalConstellationSync provides constellationSync.controller) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = StargleanColors.Ink,
            bottomBar = {
                StargleanBottomBar(selected = destination, showConstellationUnread = reactionNotices.hasUnread, onSelect = {
                    cygnusPreviewAvailable = false
                    constellationsPreviewAvailable = false
                    settingsOpen = false
                    destination = it
                })
            },
        ) { contentPadding ->
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "primary-navigation",
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            ) { current ->
                when (current) {
                    AppDestination.Planet -> if (settingsOpen) SettingsScreen(isActive = destination == AppDestination.Planet) {
                        settingsOpen = false
                    } else PlanetScreen(
                        demoCygnusPreview = cygnusPreviewAvailable,
                        demoConstellationsPreview = constellationsPreviewAvailable,
                        isActive = destination == AppDestination.Planet,
                        onOpenSettings = { settingsOpen = true },
                    )
                    AppDestination.Discover -> DiscoverScreen(
                        demoAutoTrigger = launch.discoverSuccess && launch.destination == AppDestination.Discover,
                        isActive = destination == AppDestination.Discover,
                        demoAutoReaction = launch.discoverHug,
                        demoReaction = launch.discoverReaction,
                        demoWeather = launch.discoverWeather,
                    )
                    AppDestination.Write -> writingDraftState.SaveableStateProvider("writing-draft") {
                        WriteScreen(
                            isActive = destination == AppDestination.Write,
                            demoWeather = launch.writeWeather,
                            testLiveWeather = launch.writeLiveWeatherTest,
                            demoPreview = launch.writePreview,
                            onOpenConstellations = { destination = AppDestination.Constellations },
                        )
                    }
                    AppDestination.Constellations -> galleryState.SaveableStateProvider("constellation-gallery") {
                        ConstellationsScreen(
                        demoAutoLight = launch.constellationLight,
                        isActive = destination == AppDestination.Constellations,
                        demoTour = launch.galleryTour,
                        demoPanel = launch.galleryPanel,
                        demoStar = launch.galleryStar,
                        demoPage = launch.galleryPage,
                        demoMemoryCompass = launch.memoryCompassDemo,
                        reactionNotices = reactionNotices,
                        consumeReactionAnimation = runtime.reactions::consumeAnimation,
                        )
                    }
                }
            }
        }
    }
    }
}
