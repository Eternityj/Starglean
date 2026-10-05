package com.starglean.app.core.design

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StargleanColors {
    val Ink = Color(0xFF040812)
    val InkRaised = Color(0xFF080F1D)
    val InkSoft = Color(0xFF111C2D)
    val WarmWhite = Color(0xFFF2EBDD)
    val Paper = Color(0xFFF1E8D6)
    val PaperDeep = Color(0xFFE4D7BE)
    val Gold = Color(0xFFC8A96B)
    val GoldBright = Color(0xFFF2D99B)
    val GoldMuted = Color(0xFF8D794F)
    val ColdBlue = Color(0xFF829AB5)
    val ColdBlueMuted = Color(0xFF3B536E)
    val MutedText = Color(0xFF8D99AA)
    val PaperText = Color(0xFF312D2A)
    val Error = Color(0xFFE4A4A4)
}

private val StargleanColorScheme = darkColorScheme(
    primary = StargleanColors.Gold,
    onPrimary = StargleanColors.Ink,
    secondary = StargleanColors.ColdBlue,
    background = StargleanColors.Ink,
    onBackground = StargleanColors.WarmWhite,
    surface = StargleanColors.InkRaised,
    onSurface = StargleanColors.WarmWhite,
    error = StargleanColors.Error,
)

@Composable
fun StargleanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StargleanColorScheme,
        typography = MaterialTheme.typography.copy(
            displaySmall = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 1.sp,
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 25.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Medium,
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 21.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            ),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = .2.sp),
        ),
        content = content,
    )
}

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}
