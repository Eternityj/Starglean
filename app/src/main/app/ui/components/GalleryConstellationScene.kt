package com.starglean.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.starglean.app.R
import com.starglean.app.BuildConfig
import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryFigure
import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.ui.space.ConstellationFilaments
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A quiet, native 2.5D collection chart. The translucent artwork explains the figure;
 * only the existing, named chart anchors are interactive or can represent a diary.
 * No home geometry or source artwork is changed by this renderer.
 */
@Composable
fun GalleryConstellationScene(
    item: GalleryConstellation,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    apparitionVisible: Boolean = true,
    selectedStar: Int? = null,
    onStarSelected: (Int) -> Unit = {},
    lightweight: Boolean = false,
    pageOffset: Float = 0f,
    lightingStar: Int? = null,
) {
    val clock = remember(item.id) { mutableStateOf(0.0) }
    val arrival = remember(item.id, lightingStar) { Animatable(if (lightingStar != null && motionEnabled) 0f else 1f) }
    LaunchedEffect(item.id, lightingStar, motionEnabled) {
        if (lightingStar != null && motionEnabled) arrival.animateTo(1f, tween(1900))
        else arrival.snapTo(1f)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val selectStar by rememberUpdatedState(onStarSelected)
    val normalized = remember(item.points) { item.points.map(GallerySceneMath::unitPoint) }
    val motes = remember(item.geometryIndex, item.latinName, item.points, item.links, lightweight) {
        GalleryStarDust.motes(item).let { values ->
            if (lightweight) values.filterIndexed { index, _ -> index % 4 == 0 } else values
        }
    }
    val flecks = remember(item.geometryIndex, item.latinName, item.points, item.links, lightweight) {
        if (lightweight) emptyList() else GalleryStarDust.flecks(item)
    }
    val apparitionResource = when (item.figure) {
        GalleryFigure.Cygnus -> R.drawable.cygnus_apparition
        GalleryFigure.Orion -> R.drawable.orion_apparition
        GalleryFigure.Lyra -> R.drawable.lyra_apparition
        GalleryFigure.Cassiopeia -> R.drawable.cassiopeia_apparition
        GalleryFigure.Leo -> R.drawable.leo_apparition
        GalleryFigure.Scorpius -> R.drawable.scorpius_apparition
        GalleryFigure.Pegasus -> R.drawable.pegasus_apparition
        GalleryFigure.Delphinus -> R.drawable.delphinus_apparition
        null -> null
    }
    val hasApparition = apparitionResource != null
    val figureAlpha by animateFloatAsState(
        targetValue = if (apparitionVisible && hasApparition) .30f else 0f,
        animationSpec = tween(if (motionEnabled && !lightweight) 650 else 0), label = "constellation apparition",
    )
    val safeOffset = pageOffset.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f

    LaunchedEffect(item.id, motionEnabled, lightweight, lifecycleOwner) {
        if (!motionEnabled || lightweight) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (previous != 0L) clock.value +=
                        ((now - previous) / 1_000_000_000.0).coerceIn(0.0, .05)
                    previous = now
                }
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val density = LocalDensity.current
        val sidePx = with(density) { side.toPx() }
        val hitRadius = with(density) { 24.dp.toPx() }
        Box(
            Modifier.size(side).graphicsLayer {
                val t = clock.value
                // Chart and artwork share the pose; only each figure's soft details deform.
                translationX = sin(t * .23).toFloat() * 1.8.dp.toPx() - safeOffset * size.width * .045f
                translationY = sin(t * .31).toFloat() * 2.4.dp.toPx()
                rotationZ = sin(t * .15).toFloat() * .48f
                rotationY = sin(t * .17).toFloat() * 1.3f - safeOffset * 3f
                cameraDistance = 12f * size.width.coerceAtLeast(1f)
                val breath = 1f + sin(t * .27).toFloat() * .0035f
                scaleX = breath
                scaleY = breath
            }.then(
                if (lightweight) Modifier else Modifier.pointerInput(item.id, item.points, sidePx, hitRadius) {
                    detectTapGestures { tap ->
                        GallerySceneMath.nearestStar(item.points, tap.x, tap.y, sidePx, hitRadius)
                            ?.let(selectStar)
                    }
                },
            ),
        ) {
            if (apparitionResource != null) {
                val image = ImageBitmap.imageResource(apparitionResource)
                if (lightweight) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = figureAlpha })
                } else {
                    val bitmap = remember(image) { image.asAndroidBitmap() }
                    Box(Modifier.fillMaxSize().drawWithCache {
                        val otherMesh = when (item.figure) {
                            GalleryFigure.Orion -> GalleryApparitionMesh(GalleryApparitionKind.Orion)
                            GalleryFigure.Lyra -> GalleryApparitionMesh(GalleryApparitionKind.Lyra)
                            else -> null
                        }
                        val expandedMesh = when (item.figure) {
                            GalleryFigure.Cygnus, GalleryFigure.Cassiopeia, GalleryFigure.Leo, GalleryFigure.Scorpius,
                            GalleryFigure.Pegasus, GalleryFigure.Delphinus ->
                                GalleryExpandedMesh(requireNotNull(item.figure), item.points)
                            else -> null
                        }
                        val columns = otherMesh?.columns ?: requireNotNull(expandedMesh).columns
                        val rows = otherMesh?.rows ?: requireNotNull(expandedMesh).rows
                        val vertices = otherMesh?.vertices ?: requireNotNull(expandedMesh).vertices
                        val colors = otherMesh?.colors ?: requireNotNull(expandedMesh).colors
                        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                        onDrawBehind {
                            val opacity = figureAlpha
                            if (opacity > .001f) {
                                // One seamless alpha mesh per subject. Rigid anatomy/frame stays pinned.
                                // The shared clock freezes on pause without snapping back.
                                val moving = motionEnabled || clock.value > 0.0
                                otherMesh?.update(clock.value, size.minDimension, moving)
                                expandedMesh?.update(clock.value, size.minDimension, moving)
                                paint.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawBitmapMesh(
                                        bitmap, columns, rows, vertices, 0, colors, 0, paint,
                                    )
                                }
                            }
                        }
                    })
                }
            }
            Box(Modifier.fillMaxSize().drawWithCache {
                val unit = size.minDimension
                val points = normalized.map { Offset(it.x * unit, it.y * unit) }
                val gold = Color(0xFFE5C28E)
                val ivory = Color(0xFFFFF3D7)
                val distant = Color(0xFFB8CBDD)
                val hazeRadius = (if (lightweight) .8f else 1.65f).dp.toPx()
                val hazeBrush = Brush.radialGradient(
                    0f to gold.copy(alpha = .19f), .3f to gold.copy(alpha = .065f),
                    1f to Color.Transparent, center = Offset.Zero, radius = hazeRadius,
                )
                val haze = motes.map { mote ->
                    val point = GallerySceneMath.unitPoint(GalleryPoint(mote.position.x, mote.position.y))
                    SceneGrain(Offset(point.x * unit, point.y * unit), mote.intensity,
                        (.24f + mote.scale * .68f).dp.toPx())
                }
                val sparks = flecks.map { mote ->
                    val point = GallerySceneMath.unitPoint(GalleryPoint(mote.position.x, mote.position.y))
                    SceneGrain(Offset(point.x * unit, point.y * unit), mote.intensity,
                        (.20f + mote.scale * .80f).dp.toPx())
                }
                val anchors = points.mapIndexed { index, point ->
                    val style = item.geometryIndex?.let { ConstellationFilaments.star(it, index) }
                    val prominence = style?.scale ?: 1.12f
                    val core = (if (lightweight) .85f else 1.15f + prominence * .47f).dp.toPx()
                    val halo = core * (if (lightweight) 5f else 11.5f)
                    val ember = core * 3.7f
                    SceneAnchor(
                        point, core, halo, ember,
                        Brush.radialGradient(
                            0f to gold.copy(alpha = .24f), .13f to gold.copy(alpha = .12f),
                            .42f to gold.copy(alpha = .024f), 1f to Color.Transparent,
                            center = point, radius = halo,
                        ),
                        Brush.radialGradient(
                            0f to ivory.copy(alpha = .92f), .13f to ivory.copy(alpha = .60f),
                            .37f to gold.copy(alpha = .22f), .7f to gold.copy(alpha = .032f),
                            1f to Color.Transparent, center = point, radius = ember,
                        ),
                    )
                }
                val selectedStroke = Stroke(.5.dp.toPx())
                val driftingApparitionDust = hasApparition && !lightweight
                val dustDrift = 1.8.dp.toPx()
                onDrawBehind {
                    val t = clock.value
                    val incoming = lightingStar?.takeIf { it in item.litStarIndices }?.let {
                        diaryStarLightFrame(item.points[it], arrival.value, motionEnabled)
                    }
                    // Irregular native grains keep the connections implied, not rigid strokes.
                    haze.forEachIndexed { index, grain ->
                        val breath = .82f + .18f * sin(t * .65 + index * .73).toFloat()
                        if (!lightweight) translate(grain.point.x, grain.point.y) {
                            drawCircle(hazeBrush, radius = hazeRadius, center = Offset.Zero,
                                alpha = breath * grain.opacity * 1.7f)
                        }
                        drawCircle(gold, radius = grain.radius, center = grain.point,
                            alpha = grain.opacity * breath * .74f)
                    }
                    sparks.forEachIndexed { index, grain ->
                        val flicker = .65f + .35f * sin(t * (.49 + index % 4 * .09) + index * 1.71).toFloat()
                        val driftedPoint = if (driftingApparitionDust) Offset(
                            grain.point.x + sin(t * .57 + index * 2.17).toFloat() * dustDrift,
                            grain.point.y + sin(t * .39 + index * 1.43).toFloat() * dustDrift * .72f,
                        ) else grain.point
                        drawCircle(if (index % 4 == 0) distant else ivory, grain.radius, driftedPoint,
                            alpha = grain.opacity * flicker * .82f)
                    }
                    anchors.forEachIndexed { index, anchor ->
                        val lit = index in item.litStarIndices && (index != lightingStar || incoming?.revealed != false)
                        val active = selectedStar == index
                        val breath = .89f + .11f * sin(t * .92 + index * 1.36).toFloat()
                        if (lit) {
                            drawCircle(anchor.haloBrush, anchor.halo, anchor.point,
                                alpha = breath * if (active) 1f else .88f)
                            drawCircle(anchor.emberBrush, anchor.ember, anchor.point, alpha = breath)
                            drawCircle(ivory, anchor.core, anchor.point, alpha = .91f + .09f * breath)
                            drawCircle(Color.White, anchor.core * .38f, anchor.point, alpha = .91f)
                        } else {
                            drawCircle(distant, anchor.core * .82f, anchor.point, alpha = .40f)
                            drawCircle(anchor.haloBrush, anchor.halo * .75f, anchor.point, alpha = .18f)
                        }
                        if (active && !lightweight) {
                            drawCircle(gold, radius = anchor.core * 6.1f, center = anchor.point,
                                alpha = .32f, style = selectedStroke)
                        }
                    }
                    if (incoming != null) {
                        val point = Offset(incoming.point.x * unit, incoming.point.y * unit)
                        if (incoming.travellingAlpha > 0f) {
                            val radius = 15.dp.toPx()
                            drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .2f), Color.Transparent), point, radius), radius, point)
                            drawCircle(ivory, 1.8.dp.toPx(), point, alpha = incoming.travellingAlpha)
                            repeat(5) { i -> drawCircle(gold, (.65f - i * .07f).dp.toPx(),
                                point + Offset((if (i % 2 == 0) -1 else 1) * (i + 1) * 1.1.dp.toPx(), (i + 1) * 3.dp.toPx()),
                                alpha = incoming.travellingAlpha * (5 - i) / 10f) }
                        }
                        if (incoming.arrivalAlpha > 0f) {
                            val radius = 25.dp.toPx()
                            drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = incoming.arrivalAlpha), Color.Transparent), point, radius), radius, point)
                            val reveal = ((arrival.value - .72f) / .22f).coerceIn(0f, 1f)
                            diaryStarArrivalNeighbours(item, requireNotNull(lightingStar)).forEach { neighbour ->
                                repeat(24) { grain ->
                                    val along = grain / 23f
                                    if (along <= reveal) drawCircle(gold, (.4f + grain % 3 * .12f).dp.toPx(),
                                        point + (points[neighbour] - point) * along, alpha = incoming.arrivalAlpha * .7f)
                                }
                            }
                        }
                    }
                }
            })
            if (!lightweight) normalized.forEachIndexed { index, point ->
                Box(
                    Modifier.offset {
                        IntOffset(
                            (point.x * sidePx - hitRadius).roundToInt(),
                            (point.y * sidePx - hitRadius).roundToInt(),
                        )
                    }.size(48.dp).semantics {
                        role = Role.Button
                        selected = selectedStar == index
                        contentDescription = "${item.name}, star ${index + 1}, " +
                            if (index in item.litStarIndices) "lit; view diary" else "not yet lit"
                        onClick(label = if (index in item.litStarIndices) "View diary" else "View empty star") {
                            selectStar(index)
                            true
                        }
                    },
                )
            }
        }
    }
}

private data class SceneGrain(val point: Offset, val opacity: Float, val radius: Float)

private data class SceneAnchor(
    val point: Offset,
    val core: Float,
    val halo: Float,
    val ember: Float,
    val haloBrush: Brush,
    val emberBrush: Brush,
)
