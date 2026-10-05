package com.starglean.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.location.*
import com.starglean.app.core.storage.AccountStorageContext

internal data class AppLocationAccess(
    val managed: Boolean = false,
    val consented: Boolean = false,
    val precisePermissionGranted: Boolean = false,
    val openSettings: () -> Unit = {},
) {
    val allowed: Boolean get() = mayUseAppLocation(
        if (consented) AppLocationConsent.Enabled else AppLocationConsent.Declined, precisePermissionGranted)
}

/** Standalone previews default to no permission; only the entry gate grants production consent. */
internal val LocalAppLocationAccess = staticCompositionLocalOf { AppLocationAccess() }

/** Account-scoped choice, no location, route or diary data. Android permission remains device-wide. */
@Composable
internal fun AppLocationGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val storage = context.applicationContext
    val account = (storage as? AccountStorageContext)?.accountId ?: "standalone"
    val preferences = remember(storage, account) {
        storage.getSharedPreferences("foreground_location_$account", Context.MODE_PRIVATE)
    }
    var consent by remember(preferences) {
        mutableStateOf(decodeAppLocationConsent(preferences.getInt("version", 0),
            preferences.getString("decision", null)))
    }
    var precise by remember(context) { mutableStateOf(context.hasAppPreciseLocationPermission()) }
    var settingsOpen by rememberSaveable(account) { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    fun choose(value: AppLocationConsent) {
        preferences.edit().putInt("version", appLocationConsentVersion())
            .putString("decision", value.name).apply()
        consent = value
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        precise = context.hasAppPreciseLocationPermission()
        // A denial, coarse-only choice or dismissed system sheet never prompts again by itself.
    }
    fun enable() {
        choose(AppLocationConsent.Enabled)
        settingsOpen = false
        message = null
        precise = context.hasAppPreciseLocationPermission()
        if (!precise && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            requesting = true
            try { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            catch (_: RuntimeException) {
                requesting = false
                message = "Open system settings to allow precise location. You can keep writing without it."
                settingsOpen = true
            }
        }
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) precise = context.hasAppPreciseLocationPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val welcome = consent == AppLocationConsent.NotAsked
    if (welcome) {
        BackHandler { if (!requesting) choose(AppLocationConsent.Declined) }
        LocationWelcomeScreen(requesting = requesting,
            onAllow = ::enable, onNotNow = { choose(AppLocationConsent.Declined) })
    } else {
        CompositionLocalProvider(LocalAppLocationAccess provides AppLocationAccess(
            managed = true,
            consented = consent == AppLocationConsent.Enabled && !settingsOpen && !requesting,
            precisePermissionGranted = precise,
            openSettings = { message = null; settingsOpen = true },
        )) { content() }
    }
    if (settingsOpen && !welcome) AlertDialog(
        onDismissRequest = { settingsOpen = false }, containerColor = StargleanColors.InkRaised,
        title = { Text("Location settings", color = StargleanColors.WarmWhite) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    consent != AppLocationConsent.Enabled -> "Location features are off. Local writing is still available."
                    !precise -> "Precise location is not allowed in system settings. Nearby reading and weather are paused."
                    else -> "Allowed while the relevant screen is open. No background location."
                }, color = StargleanColors.WarmWhite)
                Text(locationUseDisclosure(), color = StargleanColors.MutedText,
                    style = MaterialTheme.typography.bodyMedium)
                message?.let { Text(it, color = StargleanColors.Gold) }
                if (consent == AppLocationConsent.Enabled) TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }
                    catch (_: RuntimeException) { message = "Could not open settings. Open Starglean’s permissions in your device Settings." }
                }) { Text("Open system permissions", color = StargleanColors.Gold) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (consent != AppLocationConsent.Enabled) enable() else settingsOpen = false }) {
                Text(if (consent != AppLocationConsent.Enabled) "Enable location" else "Done", color = StargleanColors.Gold)
            }
        },
        dismissButton = { TextButton(onClick = {
            if (consent == AppLocationConsent.Enabled) choose(AppLocationConsent.Declined)
            settingsOpen = false
        }) { Text(if (consent == AppLocationConsent.Enabled) "Turn location off" else "Not now", color = StargleanColors.MutedText) } },
    )
}

private fun Context.hasAppPreciseLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun locationUseDisclosure(): String =
    "Nearby memories: precise location is sent to Starglean to check the 200 m reading rule.\n\n" +
    "Weather: precise coordinates are sent to Open-Meteo. Place names use Android’s system location service. Diary text is not sent to these providers.\n\n" +
    "Your Memory Compass and optional movement checks stay on-device and only start when you choose. Nothing is published automatically."

/** Presentation-only boundary for real layout QA without granting permissions or querying GPS. */
@Composable
internal fun LocationWelcomeScreen(requesting: Boolean, onAllow: () -> Unit, onNotNow: () -> Unit) {
    Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding()
        .padding(horizontal = 28.dp, vertical = 20.dp)) {
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("STARGLEAN", color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium,
            letterSpacing = 3.sp)
        Text("✦", color = StargleanColors.GoldBright, fontSize = 46.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        Text("Your sky,\nwherever you are", color = StargleanColors.WarmWhite, fontFamily = FontFamily.Serif,
            fontSize = 32.sp, lineHeight = 39.sp)
        Text("Use your location for nearby memories and today’s weather.", color = StargleanColors.MutedText,
            style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(22.dp))
            .border(1.dp, StargleanColors.Gold.copy(alpha = .18f), RoundedCornerShape(22.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Only while you are here", color = StargleanColors.Gold,
                style = MaterialTheme.typography.titleSmall)
            Text(locationUseDisclosure(), color = StargleanColors.MutedText,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("No background location. You can change this in Account → Location settings.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
      }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAllow, enabled = !requesting,
            colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold, contentColor = StargleanColors.Ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(27.dp)) {
            Text(if (requesting) "Waiting for your choice…" else "Allow precise location")
        }
        TextButton(onClick = onNotNow, enabled = !requesting, modifier = Modifier.fillMaxWidth()) {
            Text("Not now · Keep writing locally", color = StargleanColors.MutedText)
        }
    }
}
