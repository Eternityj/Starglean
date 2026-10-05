package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.StargleanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bundled disclosure remains available offline, including before account creation. */
@Composable
internal fun PrivacyDetailsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var loadAttempt by remember { mutableIntStateOf(0) }
    val notice by produceState<Result<JSONObject>?>(null, loadAttempt) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(context.assets.open("privacy-notice.json").bufferedReader().use {
                    parsePrivacyNotice(it.readText())
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An absent or damaged asset must not crash the sign-in/privacy entry point.
                Result.failure(IllegalStateException("The bundled privacy notice could not be loaded."))
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back", color = StargleanColors.Gold) }
        val document = notice?.getOrNull()
        Text(document?.getString("title") ?: "Privacy & sharing", color = StargleanColors.WarmWhite,
            style = MaterialTheme.typography.headlineMedium)
        if (notice == null) {
            Text("Loading the offline privacy notice…", color = StargleanColors.MutedText)
        } else if (notice?.isFailure == true) {
            Text("The privacy notice could not be loaded. Please retry or contact jasonyangjiesen@gmail.com.",
                color = StargleanColors.WarmWhite)
            TextButton(onClick = { loadAttempt++ }) { Text("Retry", color = StargleanColors.Gold) }
        }
        document?.let { document ->
            Text("Updated ${document.getString("updated")}", color = StargleanColors.MutedText,
                style = MaterialTheme.typography.labelMedium)
            val sections = document.getJSONArray("sections")
            repeat(sections.length()) { index ->
                val section = sections.getJSONObject(index)
                Text(section.getString("title"), color = StargleanColors.Gold, style = MaterialTheme.typography.titleMedium)
                Text(section.getString("text"), color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodyMedium)
            }
            val providers = document.getJSONArray("providers")
            repeat(providers.length()) { index ->
                val provider = providers.getJSONObject(index)
                TextButton(onClick = { uri.openUri(provider.getString("url")) }) { Text(provider.getString("name")) }
            }
            TextButton(onClick = { uri.openUri("mailto:${document.getString("contact")}") }) { Text("Contact the operator") }
        }
    }
}
