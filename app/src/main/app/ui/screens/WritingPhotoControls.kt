package com.starglean.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.media.DiaryCameraCache
import com.starglean.app.core.media.DiaryPhotoRules
import java.util.UUID

/** Uses system pickers: no broad gallery permission and no background camera access. */
@Composable
internal fun WritingPhotoControls(session: WritingDraftSession?, draftId: String, enabled: Boolean) {
    val context = LocalContext.current
    val cameraCache = remember(context) { DiaryCameraCache(context) }
    var pendingDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraToken by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val sessionState = session?.state?.collectAsState()?.value
    val importing = sessionState?.photoImporting == true
    LaunchedEffect(session, cameraToken) { session?.cleanupStaleCameraFiles(cameraToken) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val expected = pendingDraft
        pendingDraft = null
        if (uri != null && expected != null && session != null) session.importPhoto(uri, expected)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val token = cameraToken
        val expected = pendingDraft
        cameraToken = null
        pendingDraft = null
        if (token != null) {
            try {
                val file = cameraCache.file(token)
                if (success && expected != null && session != null) {
                    session.importPhoto(FileProvider.getUriForFile(context, "${context.packageName}.camera", file), expected, token)
                } else cameraCache.delete(token)
            } catch (_: Exception) {
                message = "The camera photo could not be opened safely."
            }
        }
    }
    val currentPhotos = sessionState?.draft?.photos.orEmpty()
    val mayAdd = enabled && session != null && !importing && pendingDraft == null &&
        currentPhotos.size < DiaryPhotoRules.MAX_ATTACHMENTS
    Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Timeless moments", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleSmall)
        if (session == null) Text(
            "Photo attachments are available in your own drafts, not sample previews.",
            color = StargleanColors.MutedText,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(enabled = mayAdd, onClick = {
                pendingDraft = draftId
                message = null
                try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: Exception) { pendingDraft = null; message = "The photo picker could not open on this device." }
            }) { Text("Choose photo", color = if (mayAdd) StargleanColors.Gold else StargleanColors.MutedText) }
            TextButton(enabled = mayAdd, onClick = {
                val token = UUID.randomUUID().toString()
                var file: java.io.File? = null
                try {
                    val cameraFile = cameraCache.file(token)
                    file = cameraFile
                    check(cameraFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    check(cameraFile.createNewFile())
                    cameraToken = token
                    pendingDraft = draftId
                    message = null
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.camera", cameraFile))
                } catch (_: Exception) {
                    file?.delete(); cameraToken = null; pendingDraft = null
                    message = "No camera is available. You can choose a photo instead."
                }
            }) { Text("Take photo", color = if (mayAdd) StargleanColors.Gold else StargleanColors.MutedText) }
        }
        if (importing) Text("Preparing your photo…", color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
        (sessionState?.photoMessage ?: message)?.let {
            Text(it, color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
        }
    }
}
