package com.starglean.app.ui.components

import com.starglean.app.core.model.GallerySamples
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryApparitionMotionTest {
    @Test fun meshHasNativeArrayLayoutAndReusesStorage() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            val vertices = mesh.vertices
            val colors = mesh.colors
            assertEquals(kind, mesh.kind)
            assertEquals(48, mesh.columns)
            assertEquals(48, mesh.rows)
            assertEquals(49 * 49 * 2, vertices.size)
            assertEquals(49 * 49, colors.size)
            repeat(120) { mesh.update(it / 60.0, 411f) }
            assertSame(vertices, mesh.vertices)
            assertSame(colors, mesh.colors)
        }
    }

    @Test fun stillFrameIsUndeformedAndWhiteAtAnyTime() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            for (time in listOf(0.0, 1.8, 4.7, Double.NaN)) {
                mesh.update(time, 500f, motionEnabled = false)
                assertStill(mesh, 500f)
            }
        }
    }

    @Test fun invalidInputsProduceFiniteSafeStillFrames() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, -Double.MAX_VALUE, 0.0, 2.1)) {
                for (side in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                    -1f, 0f, 411f, Float.MAX_VALUE)) {
                    mesh.update(time, side)
                    assertTrue(mesh.vertices.all(Float::isFinite))
                    if (!side.isFinite() || side <= 0f) assertStill(mesh, 0f)
                    else if (!time.isFinite()) assertStill(mesh, side)
                }
            }
        }
    }

    @Test fun everyStarAndItsImmediateNeighbourhoodStayPinned() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            val geometryIndex = if (kind == GalleryApparitionKind.Orion) 1 else 2
            val anchors = GallerySamples.items.first { it.geometryIndex == geometryIndex }
                .points.map(GallerySceneMath::unitPoint)
            repeat(96) { frame ->
                mesh.update(frame / 10.0, 1f)
                anchors.forEach { anchor ->
                    for (dx in listOf(-.015f, 0f, .015f)) for (dy in listOf(-.015f, 0f, .015f)) {
                        val x = anchor.x + dx
                        val y = anchor.y + dy
                        assertTrue("$kind anchor $anchor at $frame", displacement(mesh, x, y) < .00001f)
                    }
                }
            }
        }
    }

    @Test fun orionMovesOnlyOutsideCloakAndDoesNotBendHisBodyOrBow() {
        val mesh = GalleryApparitionMesh(GalleryApparitionKind.Orion)
        mesh.update(1.2, 1f)
        listOf(.19f to .61f, .90f to .66f).forEach { (x, y) ->
            val movement = displacement(mesh, x, y)
            assertTrue("cloak $x,$y displacement $movement", movement > .008f)
            assertTrue(movement <= if (x < .5f) .031f else .023f)
        }
        repeat(48) { frame ->
            mesh.update(frame / 5.0, 1f)
            listOf(.4864f to .1256f, .50f to .32f, .50f to .55f,
                .40f to .67f, .60f to .78f, .90f to .42f, .12f to .45f,
                .27f to .53f, .29f to .48f, .755f to .435f, .821f to .48f,
                .839f to .505f, .858f to .30f, .818f to .565f, .79f to .649f,
                .738f to .739f, .789f to .461f, .76f to .60f, .725f to .746f).forEach { (x, y) ->
                assertTrue("body/bow $x,$y must be rigid", displacement(mesh, x, y) < .00001f)
            }
        }
    }

    @Test fun lyraMovesOnlyInnerStringsAndKeepsTheFrameRigid() {
        val mesh = GalleryApparitionMesh(GalleryApparitionKind.Lyra)
        mesh.update(0.0, 1f)
        val stringMovement = displacement(mesh, .54f, .61f)
        assertTrue("strings must gently move", stringMovement > .0005f)
        assertTrue(stringMovement <= .0027f)
        repeat(72) { frame ->
            mesh.update(frame / 10.0, 1f)
            listOf(.51f to .46f, .65f to .515f, .57f to .775f, .43f to .715f,
                .58f to .4875f, .61f to .645f, .50f to .745f, .47f to .5875f,
                .535f to .82f, .32f to .56f).forEach { (x, y) ->
                assertTrue("lyre frame $x,$y must stay rigid", displacement(mesh, x, y) < .00001f)
            }
        }
    }

    @Test fun v21CloakHasStrongerLeftMotionButPreservesTheNarrowRightTailLimit() {
        assertEquals(8.4, GalleryApparitionMesh.ORION_PERIOD_SECONDS, 0.0)
        val mesh = GalleryApparitionMesh(GalleryApparitionKind.Orion)
        var leftPeak = 0f
        var rightPeak = 0f
        repeat(168) { frame ->
            mesh.update(frame * GalleryApparitionMesh.ORION_PERIOD_SECONDS / 168.0, 1f)
            leftPeak = maxOf(leftPeak, displacement(mesh, .19f, .61f))
            rightPeak = maxOf(rightPeak, displacement(mesh, .90f, .66f))
        }
        assertTrue("left cloak peak $leftPeak must exceed the V19 excursion", leftPeak in 0.0285f..0.031f)
        assertTrue("right cloak peak $rightPeak must keep its safe original amplitude", rightPeak in 0.0215f..0.023f)
    }

    @Test fun v21StringsHaveFortyPercentMoreMotionAndAQuickerPeriod() {
        assertEquals(6.3, GalleryApparitionMesh.LYRA_PERIOD_SECONDS, 0.0)
        val mesh = GalleryApparitionMesh(GalleryApparitionKind.Lyra)
        var peak = 0f
        repeat(126) { frame ->
            mesh.update(frame * GalleryApparitionMesh.LYRA_PERIOD_SECONDS / 126.0, 1f)
            peak = maxOf(peak, displacement(mesh, .54f, .61f))
        }
        assertTrue("inner string peak $peak must exceed the V19 excursion", peak in 0.0024f..0.0027f)
    }

    @Test fun imageBoundaryIsFixedAndExcursionRemainsInsideSafeLimits() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            val maximum = if (kind == GalleryApparitionKind.Orion) .031f else .0027f
            repeat(96) { frame ->
                mesh.update(frame / 10.0, 1f)
                for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
                    val index = row * (mesh.columns + 1) + column
                    val x = column.toFloat() / mesh.columns
                    val y = row.toFloat() / mesh.rows
                    val dx = mesh.vertices[index * 2] - x
                    val dy = mesh.vertices[index * 2 + 1] - y
                    assertTrue("$kind excursion", hypot(dx, dy) <= maximum)
                    assertTrue(mesh.vertices[index * 2] in 0f..1f)
                    assertTrue(mesh.vertices[index * 2 + 1] in 0f..1f)
                    if (row == 0 || column == 0 || row == mesh.rows || column == mesh.columns) {
                        assertEquals(0f, dx, 0f)
                        assertEquals(0f, dy, 0f)
                        assertEquals(-1, mesh.colors[index])
                    }
                }
            }
        }
    }

    @Test fun everyTriangleRetainsPositiveAreaAcrossEachCycle() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            repeat(192) { frame ->
                mesh.update(frame / 20.0, 1f)
                for (row in 0 until mesh.rows) for (column in 0 until mesh.columns) {
                    val a = row * (mesh.columns + 1) + column
                    val b = a + 1
                    val c = a + mesh.columns + 1
                    val d = c + 1
                    assertTrue("$kind top triangle at $frame $row $column", cross(mesh, a, b, c) > 0f)
                    assertTrue("$kind bottom triangle at $frame $row $column", cross(mesh, b, d, c) > 0f)
                }
            }
        }
    }

    @Test fun loopsJoinWithoutMotionOrLightingSeams() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            val period = if (kind == GalleryApparitionKind.Orion)
                GalleryApparitionMesh.ORION_PERIOD_SECONDS else GalleryApparitionMesh.LYRA_PERIOD_SECONDS
            for (time in listOf(0.0, .7, 1.8, 4.9, -1.7)) {
                mesh.update(time, 300f)
                val vertices = mesh.vertices.copyOf()
                val colors = mesh.colors.copyOf()
                mesh.update(time + period, 300f)
                vertices.indices.forEach { assertEquals(vertices[it], mesh.vertices[it], .00005f) }
                colors.indices.forEach { assertEquals(colors[it], mesh.colors[it]) }
            }
            mesh.update(-.0001, 1f)
            val before = mesh.vertices.copyOf()
            mesh.update(.0001, 1f)
            before.indices.forEach { assertTrue(abs(before[it] - mesh.vertices[it]) < .00001f) }
        }
    }

    @Test fun flowLightingModulatesOnlyAlphaWithinSubtleBounds() {
        GalleryApparitionKind.entries.forEach { kind ->
            val mesh = GalleryApparitionMesh(kind)
            var leastAlpha = 255
            repeat(96) { frame ->
                mesh.update(frame / 10.0, 1f)
                mesh.colors.forEach { color ->
                    val alpha = color ushr 24
                    assertTrue(alpha in 210..255)
                    assertEquals(0x00ffffff, color and 0x00ffffff)
                    leastAlpha = minOf(leastAlpha, alpha)
                }
            }
            assertTrue("$kind should have a visible light change", leastAlpha < 230)
        }
    }

    @Test fun eachIllustrationUsesItsOwnMovementRatherThanSwanFlapping() {
        val orion = GalleryApparitionMesh(GalleryApparitionKind.Orion)
        val lyra = GalleryApparitionMesh(GalleryApparitionKind.Lyra)
        orion.update(1.2, 1f)
        lyra.update(1.2, 1f)
        assertFalse(orion.vertices.contentEquals(lyra.vertices))
        assertFalse(orion.colors.contentEquals(lyra.colors))
        assertTrue(displacement(orion, .19f, .61f) > .008f)
        assertTrue(displacement(lyra, .19f, .61f) < .00001f)
        assertTrue(displacement(orion, .54f, .61f) < .00001f)
    }

    private fun assertStill(mesh: GalleryApparitionMesh, side: Float) {
        for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
            val index = row * (mesh.columns + 1) + column
            assertEquals(column.toFloat() / mesh.columns * side, mesh.vertices[index * 2], 0f)
            assertEquals(row.toFloat() / mesh.rows * side, mesh.vertices[index * 2 + 1], 0f)
            assertEquals(-1, mesh.colors[index])
        }
    }

    private fun displacement(mesh: GalleryApparitionMesh, x: Float, y: Float): Float {
        val moved = sample(mesh, x, y)
        return hypot(moved.first - x, moved.second - y)
    }

    private fun sample(mesh: GalleryApparitionMesh, x: Float, y: Float): Pair<Float, Float> {
        val cellX = floor(x * mesh.columns).toInt().coerceIn(0, mesh.columns - 1)
        val cellY = floor(y * mesh.rows).toInt().coerceIn(0, mesh.rows - 1)
        val tx = x * mesh.columns - cellX
        val ty = y * mesh.rows - cellY
        val a = cellY * (mesh.columns + 1) + cellX
        val b = a + 1
        val c = a + mesh.columns + 1
        val d = c + 1
        fun component(offset: Int): Float =
            (mesh.vertices[a * 2 + offset] * (1f - tx) + mesh.vertices[b * 2 + offset] * tx) * (1f - ty) +
                (mesh.vertices[c * 2 + offset] * (1f - tx) + mesh.vertices[d * 2 + offset] * tx) * ty
        return component(0) to component(1)
    }

    private fun cross(mesh: GalleryApparitionMesh, a: Int, b: Int, c: Int): Float {
        val ax = mesh.vertices[a * 2]
        val ay = mesh.vertices[a * 2 + 1]
        return (mesh.vertices[b * 2] - ax) * (mesh.vertices[c * 2 + 1] - ay) -
            (mesh.vertices[b * 2 + 1] - ay) * (mesh.vertices[c * 2] - ax)
    }
}
