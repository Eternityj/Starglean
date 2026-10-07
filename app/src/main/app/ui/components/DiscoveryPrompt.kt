package com.starglean.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starglean.app.core.design.StargleanColors

/** One quiet, readable inscription; an empty result is never inferred from a failed request. */
@Composable
internal fun DiscoveryPrompt(noEligibleContent: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 112.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (noEligibleContent) "No stars can fall here." else "Shake for a star",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            fontFamily = FontFamily.Cursive,
            fontSize = 36.sp,
            lineHeight = 48.sp,
            color = StargleanColors.WarmWhite.copy(alpha = .72f),
            textAlign = TextAlign.Center,
        )
    }
}
