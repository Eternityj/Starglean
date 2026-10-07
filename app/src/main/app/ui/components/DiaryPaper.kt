package com.starglean.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import kotlin.math.sin

@Composable
fun DemoBadge(modifier: Modifier = Modifier, text: String = "Local demo") {
    Text(
        text,
        color = StargleanColors.Gold,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .border(1.dp, StargleanColors.Gold.copy(alpha = .32f), RoundedCornerShape(50))
            .background(StargleanColors.Gold.copy(alpha = .07f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun DiaryPaper(
    diary: DiaryDemo,
    modifier: Modifier = Modifier,
    showDemoBadge: Boolean = true,
    readingLayout: Boolean = false,
    minimumHeight: Dp = 0.dp,
    placeLabel: String? = null,
    reactionContent: (@Composable () -> Unit)? = null,
    photoContent: (@Composable () -> Unit)? = null,
) {
    val (paperBrush, primaryText, mutedText) = when (diary.paper) {
        PaperStyle.WarmWhite -> Triple(
            Brush.verticalGradient(listOf(Color(0xFFF5EEDF), Color(0xFFE8DCC6))),
            StargleanColors.PaperText,
            Color(0xFF746B61),
        )
        PaperStyle.Kraft -> Triple(
            Brush.verticalGradient(listOf(Color(0xFFD5B98D), Color(0xFFB99567))),
            Color(0xFF2F261E),
            Color(0xFF705D48),
        )
        PaperStyle.Midnight -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF13263B), Color(0xFF0B192B))),
            Color(0xFFF1E9D8),
            Color(0xFF9FAFC1),
        )
    }
    val family = when (diary.font) {
        DiaryFont.Clear -> FontFamily.SansSerif
        DiaryFont.Handwritten -> FontFamily.Cursive
        DiaryFont.Serif -> FontFamily.Serif
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = Color(0x24000000),
                spotColor = StargleanColors.Gold.copy(alpha = .11f),
            )
            .background(paperBrush, RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val textureColor = primaryText.copy(alpha = if (diary.paper == PaperStyle.Midnight) .035f else .025f)
            repeat(34) { index ->
                val x = ((index * 67) % 101) / 101f * size.width
                val y = ((index * 43 + 17) % 97) / 97f * size.height
                val length = 5f + (index % 5) * 2.4f
                val bend = sin(index * 1.73f) * 1.8f
                drawLine(
                    color = textureColor,
                    start = Offset(x, y),
                    end = Offset(x + length, y + bend),
                    strokeWidth = if (index % 4 == 0) .8f else .45f,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                color = Color.White.copy(alpha = if (diary.paper == PaperStyle.Midnight) .035f else .14f),
                start = Offset(0f, 1f),
                end = Offset(size.width, 1f),
                strokeWidth = 1f,
            )
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = (minimumHeight - 48.dp).coerceAtLeast(0.dp)),
            verticalArrangement = if (readingLayout) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (readingLayout) 24.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(diary.date, color = mutedText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(diary.weather, color = mutedText, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                diary.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = primaryText, style = if (readingLayout)
                        MaterialTheme.typography.titleLarge.copy(fontFamily = family, fontSize = 26.sp, lineHeight = 36.sp)
                        else MaterialTheme.typography.titleLarge.copy(fontFamily = family))
                }
                Text(
                    diary.body,
                    color = primaryText,
                    style = if (readingLayout) MaterialTheme.typography.bodyLarge.copy(fontFamily = family, fontSize = 19.sp, lineHeight = 34.sp)
                        else MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
                )
                if (photoContent != null) photoContent() else DiaryPhotos(diary.photos)
            }
            Column {
                if (readingLayout) {
                    // This is part of the stationery, never a feedback card. Keeping its
                    // size stable means selecting a reaction cannot reflow the diary text.
                    Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                        reactionContent?.invoke()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(placeLabel ?: "Near ${diary.blurredPlace}",
                        color = mutedText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (showDemoBadge) {
                        if (readingLayout) Text("Local sample", color = mutedText, style = MaterialTheme.typography.labelSmall)
                        else DemoBadge(text = "Demo diary")
                    }
                }
            }
        }
    }
}
