package com.starglean.app.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.starglean.app.core.account.*
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.core.storage.AccountLibraryException
import com.starglean.app.core.storage.AccountLibraryPreparation
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.core.storage.AccountLibraryUiContext
import com.starglean.app.core.storage.LegacyLibraryClaimStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AppEntryGate(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val access by runtime.appAccess.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) {
        if (runtime.appAccess.state.value == AppAccessState.Loading) runtime.appAccess.load()
    }
    LaunchedEffect(runtime, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { runtime.appAccess.onForeground(); delay(60_000) }
        }
    }
    DisposableEffect(runtime, owner) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) scope.launch {
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runtime.appAccess.onForeground()
                }
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    val surface: @Composable () -> Unit = {
        AppEntrySurface(access, onRetry = runtime.appAccess::load,
            login = { message -> LoginEntry(runtime, message) }, content = content)
    }
    when (val current = access) {
        is AppAccessState.Granted -> key(current.account.userId) {
            AccountLibraryGate(current.account.userId, onSignOut = runtime.appAccess::signOut) { scopedContext ->
                AccountLibraryContent(scopedContext) { surface() }
            }
        }
        else -> surface()
    }
}

/** Preserves the host Activity owner chain while redirecting all application storage to the account. */
@Composable
internal fun AccountLibraryContent(scopedContext: AccountStorageContext, content: @Composable () -> Unit) {
    LaunchedEffect(scopedContext) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.starglean.app.core.backup.BackupScratch.cleanup(scopedContext) }
        }
    }
    val parentUiContext = LocalContext.current
    val uiContext = remember(parentUiContext, scopedContext) {
        AccountLibraryUiContext(parentUiContext, scopedContext)
    }
    CompositionLocalProvider(LocalContext provides uiContext, content = content)
}

/** Separate, testable rendering boundary: protected content is never composed while locked. */
@Composable
internal fun AppEntrySurface(
    state: AppAccessState,
    onRetry: () -> Unit,
    login: @Composable (String?) -> Unit,
    content: @Composable () -> Unit,
) {
    var recoverWithEmail by remember(state is AppAccessState.Locked && state.reason == AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE) {
        mutableStateOf(false)
    }
    when (state) {
        AppAccessState.Loading -> GateMessage("Gathering your stars…", loading = true)
        is AppAccessState.Locked -> if (state.reason == AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE) {
            if (recoverWithEmail) login("Verify your email to replace the saved sign-in. Your local diaries and drafts will not be deleted.")
            else GateMessage("Your sign-in is safely locked", detail = state.message, onRetry = onRetry,
                onRecover = { recoverWithEmail = true })
        } else login(state.message.takeUnless { state.reason == AppAccessLockReason.SIGN_IN_REQUIRED && it == "Sign in with email to continue." })
        is AppAccessState.Granted -> {
            Column(Modifier.fillMaxSize().background(StargleanColors.Ink)) {
                if (state.connectivity == AccessConnectivity.OFFLINE_VERIFIED_SESSION) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().background(StargleanColors.InkSoft)
                        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Offline · Local memories and drafts are available", Modifier.weight(1f),
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

private sealed interface LibraryGateState {
    data object Loading : LibraryGateState
    data object Choice : LibraryGateState
    data class Ready(val context: AccountStorageContext) : LibraryGateState
    data class Error(val message: String) : LibraryGateState
}

@Composable
private fun AccountLibraryGate(
    accountId: String,
    onSignOut: () -> Unit,
    content: @Composable (AccountStorageContext) -> Unit,
) {
    val base = LocalContext.current.applicationContext
    val store = remember(base) { LegacyLibraryClaimStore(base) }
    val scope = rememberCoroutineScope()
    var state by remember(accountId) { mutableStateOf<LibraryGateState>(LibraryGateState.Loading) }
    var busy by remember(accountId) { mutableStateOf(false) }

    fun load() {
        if (busy) return
        busy = true
        scope.launch {
            state = try {
                when (val prepared = store.prepare(accountId)) {
                    AccountLibraryPreparation.ChoiceRequired -> LibraryGateState.Choice
                    is AccountLibraryPreparation.Ready -> LibraryGateState.Ready(prepared.context)
                }
            } catch (error: AccountLibraryException) {
                LibraryGateState.Error(error.message ?: "Private library preparation failed.")
            } finally { busy = false }
        }
    }
    LaunchedEffect(accountId) { load() }
    when (val current = state) {
        LibraryGateState.Loading -> GateMessage("Opening your private library…", loading = true)
        LibraryGateState.Choice -> LibraryChoice(busy, onClaim = {
                busy = true; scope.launch { state = try { LibraryGateState.Ready(store.claimLegacy(accountId)) }
                    catch (error: AccountLibraryException) { LibraryGateState.Error(error.message ?: "The existing library could not be claimed safely.") }
                    finally { busy = false } }
            }, onFresh = {
                busy = true; scope.launch { state = try { LibraryGateState.Ready(store.startFresh(accountId)) }
                    catch (error: AccountLibraryException) { LibraryGateState.Error(error.message ?: "A separate library could not be created.") }
                    finally { busy = false } }
            }, onSignOut = onSignOut)
        is LibraryGateState.Ready -> content(current.context)
        is LibraryGateState.Error -> GateMessage("Your private library is locked", current.message, onRetry = {
            state = LibraryGateState.Loading; load()
        }, onSignOut = onSignOut)
    }
}

internal enum class AccountLibraryGatePreviewState { Choice, Error }

/** Rendering-only QA entry point: it never reads auth or device storage. */
@Composable
internal fun AccountLibraryGatePreview(state: AccountLibraryGatePreviewState, onSignOut: () -> Unit = {}) {
    when (state) {
        AccountLibraryGatePreviewState.Choice -> LibraryChoice(false, {}, {}, onSignOut)
        AccountLibraryGatePreviewState.Error -> GateMessage("Your private library is locked",
            "An incomplete account library copy was preserved for recovery.", onRetry = {}, onSignOut = onSignOut)
    }
}

@Composable
private fun LibraryChoice(busy: Boolean, onClaim: () -> Unit, onFresh: () -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding().padding(30.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Existing memories were found on this device", color = StargleanColors.WarmWhite,
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("Choose whether this signed-in account should receive a private copy. The original device files are kept either way, and another account cannot see a completed account library.",
            color = StargleanColors.MutedText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, bottom = 22.dp))
        Button(enabled = !busy, onClick = onClaim, modifier = Modifier.fillMaxWidth()) { Text("Use existing device library") }
        TextButton(enabled = !busy, onClick = onFresh, modifier = Modifier.fillMaxWidth()) { Text("Start with a separate empty library") }
        Text("Nothing is uploaded by either choice.", color = StargleanColors.MutedText,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
        TextButton(enabled = !busy, onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out and use another account", color = StargleanColors.WarmWhite)
        }
    }
}

@Composable
private fun LoginEntry(runtime: OnlineRuntime, message: String?) {
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    var formMode by rememberSaveable { mutableStateOf(EmailOtpIntent.EXISTING_ACCOUNT.name) }
    var formEmail by rememberSaveable { mutableStateOf("") }
    var formSignupConsent by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val controller = remember(runtime, scope) {
        AccountController(
            runtime.auth,
            scope,
            onBack = {},
            initialFormDraft = AccountFormDraft(EmailOtpIntent.valueOf(formMode), formEmail, formSignupConsent),
            onFormDraftChanged = { draft ->
                formMode = draft.mode.name
                formEmail = draft.email
                formSignupConsent = draft.signupConsent
            },
        )
    }
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    // Do not load cached account fields here: the access controller has already decided
    // they are not a usable verified session. Only a successful new verification unlocks.
    if (privacyOpen) Box(Modifier.fillMaxSize().background(StargleanColors.Ink)
        .statusBarsPadding().navigationBarsPadding()) {
        PrivacyDetailsScreen { privacyOpen = false }
    } else MandatoryLoginScreen(state, controller::dispatch, onPrivacy = { privacyOpen = true }, accessMessage = message)
}

@Composable
private fun GateMessage(title: String, detail: String? = null, loading: Boolean = false,
    onRetry: (() -> Unit)? = null, onRecover: (() -> Unit)? = null, onSignOut: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LoginSkyMark(Modifier.size(64.dp))
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = StargleanColors.WarmWhite,
            textAlign = TextAlign.Center)
        if (detail != null) Text(detail, Modifier.padding(top = 14.dp), color = StargleanColors.MutedText,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (loading) CircularProgressIndicator(Modifier.padding(top = 24.dp).size(20.dp),
            color = StargleanColors.Gold, strokeWidth = 2.dp)
        if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
            Text("Retry secure sign-in", color = StargleanColors.Gold)
        }
        if (onRecover != null) TextButton(onClick = onRecover) {
            Text("Use email sign-in", color = StargleanColors.MutedText)
        }
        if (onSignOut != null) TextButton(onClick = onSignOut) {
            Text("Sign out and use another account", color = StargleanColors.WarmWhite)
        }
    }
}
