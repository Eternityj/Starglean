package com.starglean.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.StargleanColors

@Composable
internal fun PublishingLocationCard(
    message: String,
    ready: Boolean,
    optedIn: Boolean,
    busy: Boolean,
    actionLabel: String? = null,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val accent = if (ready) StargleanColors.GoldBright else StargleanColors.ColdBlue
    Column(
        Modifier.fillMaxWidth()
            .background(StargleanColors.InkRaised.copy(alpha = .94f), shape)
            .border(1.dp, accent.copy(alpha = if (ready) .34f else .22f), shape)
            .clip(shape)
            .background(accent.copy(alpha = .055f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.size(8.dp).background(accent, CircleShape))
            Text(
                when {
                    ready -> "LOCATION READY"
                    optedIn -> "CHECKING LOCATION"
                    else -> "FRESH LOCATION"
                },
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            message,
            color = if (ready) StargleanColors.WarmWhite else StargleanColors.MutedText,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!ready) {
            // Outlined, so it never competes with the page's main Publish button.
            OutlinedButton(
                onClick = onRetry,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, StargleanColors.Gold.copy(alpha = .5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StargleanColors.Gold),
            ) {
                if (busy) CircularProgressIndicator(
                    Modifier.size(18.dp), color = StargleanColors.Gold, strokeWidth = 2.dp,
                ) else Text(actionLabel ?: if (optedIn) "Retry location" else "Use fresh location")
            }
        }
    }
}
