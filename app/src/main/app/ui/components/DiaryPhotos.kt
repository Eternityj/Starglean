package com.starglean.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local attachments only. Anonymous media must use a separately authorized reader. */
@Composable
fun DiaryPhotos(photos: List<DiaryPhoto>, modifier: Modifier = Modifier,
    onRemove: ((DiaryPhoto) -> Unit)? = null, enabled: Boolean = true) {
    var selected by remember { mutableStateOf<DiaryPhoto?>(null) }
    LaunchedEffect(photos) { if (selected !in photos) selected = null }
    if (photos.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        photos.forEachIndexed { index, photo ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LocalDiaryPhoto(photo, "Photo ${index + 1} of ${photos.size}",
                    Modifier.fillMaxWidth().aspectRatio(if (photos.size == 1) 1.5f else 1f)
                        .clip(RoundedCornerShape(12.dp)).clickable { selected = photo })
                if (onRemove != null) TextButton(onClick = { onRemove(photo) }, enabled = enabled) {
                    Text("Remove photo ${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = StargleanColors.Gold)
                }
            }
        }
    }
    selected?.let { photo ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("A moment in your diary", color = StargleanColors.WarmWhite, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = null }) { Text("Close", color = StargleanColors.Gold) }
                }
                LocalDiaryPhoto(photo, "Diary photo", Modifier.fillMaxWidth().weight(1f), fullSize = true)
                Text("On this device · Local copy", color = StargleanColors.MutedText,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun LocalDiaryPhoto(photo: DiaryPhoto, description: String, modifier: Modifier, fullSize: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, photo.ref, fullSize) {
        value = withContext(Dispatchers.IO) {
            try {
                val store = AndroidPhotoStore(context)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                store.open(photo.ref).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth !in 1..DiaryPhotoRules.MAX_LONG_EDGE ||
                    bounds.outHeight !in 1..DiaryPhotoRules.MAX_LONG_EDGE) return@withContext null
                val edge = if (fullSize) 1536 else 512
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > edge)
                        inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                }
                store.open(photo.ref).use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { null }
            catch (_: Exception) { null }
        }
    }
    Box(modifier.background(Color.Black.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, contentDescription = description, modifier = Modifier.fillMaxSize(),
                contentScale = if (fullSize) ContentScale.Fit else ContentScale.Crop)
        } ?: Text("Photo unavailable", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
    }
}
