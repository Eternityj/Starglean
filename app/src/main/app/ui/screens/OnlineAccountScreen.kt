package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.starglean.app.core.account.AccountController
import com.starglean.app.core.online.OnlineRuntime

@Composable
internal fun OnlineAccountScreen(onBack: () -> Unit) {
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    if (showPrivacy) {
        PrivacyDetailsScreen(onBack = { showPrivacy = false })
        return
    }
    val context = LocalContext.current
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    val controller = remember(runtime, scope) { AccountController(runtime.auth, scope, onBack = { latestBack() }) }
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.loadLocalSession() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    BackHandler { if (!state.busy) onBack() }
    val locationAccess = LocalAppLocationAccess.current
    AccountScreen(state, controller::dispatch, onPrivacy = { showPrivacy = true },
        onLocationSettings = locationAccess.openSettings.takeIf { locationAccess.managed })
}
