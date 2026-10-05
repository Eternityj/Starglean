package com.starglean.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.model.AppDestination

private enum class Glyph { Planet, Discover, Write, Constellation }

@Composable
private fun NavigationGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val c = center
        when (glyph) {
            Glyph.Planet -> {
                drawCircle(color, size.minDimension * .26f, c, style = stroke)
                drawArc(color, 22f, 145f, false, topLeft = Offset(c.x - size.width * .36f, c.y - size.height * .16f), size = androidx.compose.ui.geometry.Size(size.width * .72f, size.height * .32f), style = stroke)
                drawCircle(color, 1.5.dp.toPx(), Offset(size.width * .68f, size.height * .24f))
            }
            Glyph.Discover -> {
                drawCircle(color, 2.2.dp.toPx(), c)
                drawCircle(color.copy(alpha = .5f), size.minDimension * .23f, c, style = stroke)
                drawCircle(color.copy(alpha = .2f), size.minDimension * .38f, c, style = Stroke(1.dp.toPx()))
            }
            Glyph.Write -> {
                val path = Path().apply {
                    moveTo(size.width * .28f, size.height * .72f)
                    lineTo(size.width * .36f, size.height * .5f)
                    lineTo(size.width * .69f, size.height * .18f)
                    lineTo(size.width * .81f, size.height * .3f)
                    lineTo(size.width * .48f, size.height * .63f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color.copy(alpha = .55f), Offset(size.width * .25f, size.height * .8f), Offset(size.width * .7f, size.height * .8f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
            }
            Glyph.Constellation -> {
                val points = listOf(Offset(.19f, .68f), Offset(.4f, .31f), Offset(.63f, .48f), Offset(.82f, .2f))
                    .map { Offset(it.x * size.width, it.y * size.height) }
                points.zipWithNext().forEach { (a, b) -> drawLine(color.copy(alpha = .62f), a, b, 1.3.dp.toPx()) }
                points.forEachIndexed { index, point -> drawCircle(color, if (index == 1) 2.5.dp.toPx() else 1.8.dp.toPx(), point) }
            }
        }
    }
}

@Composable
fun StargleanBottomBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    showConstellationUnread: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(30.dp)),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF0080D17),
            border = BorderStroke(1.dp, Color(0x384F607A)),
            tonalElevation = 0.dp,
        ) {
            Row(
                Modifier.height(66.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppDestination.entries.forEach { destination ->
                    val isSelected = destination == selected
                    val color = if (isSelected) StargleanColors.GoldBright else Color(0xFF8791A0)
                    val glyph = when (destination) {
                        AppDestination.Planet -> Glyph.Planet
                        AppDestination.Discover -> Glyph.Discover
                        AppDestination.Write -> Glyph.Write
                        AppDestination.Constellations -> Glyph.Constellation
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(58.dp)
                            .semantics {
                                this.selected = isSelected
                                role = Role.Tab
                                contentDescription = destination.label
                            }
                            .clickable { onSelect(destination) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .drawBehind {
                                    if (isSelected) {
                                        drawCircle(StargleanColors.Gold.copy(alpha = .035f), radius = size.minDimension * .54f)
                                        drawCircle(StargleanColors.Gold.copy(alpha = .1f), radius = size.minDimension * .38f)
                                        drawCircle(
                                            color = StargleanColors.GoldBright.copy(alpha = .18f),
                                            radius = size.minDimension * .34f,
                                            style = Stroke(width = .7.dp.toPx()),
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(width = 31.dp, height = 31.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                NavigationGlyph(glyph, color, Modifier.size(29.dp))
                            }
                            if (isSelected) {
                                Canvas(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .size(width = 16.dp, height = 5.dp),
                                ) {
                                    drawCircle(
                                        StargleanColors.GoldBright,
                                        radius = 1.8.dp.toPx(),
                                        center = center,
                                    )
                                }
                            }
                            if (destination == AppDestination.Constellations && showConstellationUnread) {
                                Canvas(
                                    Modifier.align(Alignment.TopEnd).size(12.dp)
                                        .semantics { contentDescription = "New reactions to your memories" },
                                ) {
                                    drawCircle(StargleanColors.Ink, radius = 4.5.dp.toPx())
                                    drawCircle(StargleanColors.GoldBright, radius = 3.dp.toPx())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
