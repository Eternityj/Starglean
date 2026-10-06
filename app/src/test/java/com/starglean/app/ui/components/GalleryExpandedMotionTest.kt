package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryFigure
import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.core.model.GallerySamples
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryExpandedMotionTest {
    private val figures = listOf(GalleryFigure.Cassiopeia, GalleryFigure.Leo, GalleryFigure.Scorpius,
        GalleryFigure.Pegasus, GalleryFigure.Delphinus)

    @Test fun nativeMeshArraysKeepTheirIdentityAcrossFramesAndSizes() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            val vertices = mesh.vertices
            val colors = mesh.colors
            assertEquals(figure, mesh.figure)
            assertEquals(48, mesh.columns)
            assertEquals(48, mesh.rows)
            assertEquals(49 * 49 * 2, vertices.size)
            assertEquals(49 * 49, colors.size)
            assertTrue(mesh.periodSeconds in 6.0..10.0)
            repeat(120) { mesh.update(it / 30.0, if (it % 2 == 0) 411f else 720f) }
            assertSame(vertices, mesh.vertices)
            assertSame(colors, mesh.colors)
        }
    }

    @Test fun reducedMotionIsAlwaysAnUndeformedWhiteFrame() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            for (time in listOf(0.0, 1.8, 7.4, Double.NaN, Double.MAX_VALUE)) {
                mesh.update(time, 500f, motionEnabled = false)
                assertStill(mesh, 500f)
            }
        }
    }

    @Test fun invalidInputsNeverEscapeIntoNativeDrawing() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
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

    @Test fun nativeStarAndItsInterpolatedNeighbourhoodRemainRigidAndFullyLit() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            val anchors = points(figure).map(GallerySceneMath::unitPoint)
            repeat(80) { frame ->
                mesh.update(frame * mesh.periodSeconds / 80, 1f)
                anchors.forEach { anchor ->
                    for (dx in listOf(-.015f, 0f, .015f)) for (dy in listOf(-.015f, 0f, .015f)) {
                        assertTrue("$figure anchor neighbourhood at $frame", displacement(mesh, anchor.x + dx, anchor.y + dy) < .00001f)
                    }
                    for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
                        val x = column.toFloat() / mesh.columns
                        val y = row.toFloat() / mesh.rows
                        if (hypot(x - anchor.x, y - anchor.y) <= .055f) {
                            assertEquals(-1, mesh.colors[row * (mesh.columns + 1) + column])
                        }
                    }
                }
            }
        }
    }

    @Test fun suppliedPointsAreSnapshotPinnedRatherThanReadFromGlobalCatalog() {
        figures.forEach { figure ->
            val custom = mutableListOf(GalleryPoint(-.30f, -.36f), GalleryPoint(.38f, .40f))
            val anchors = custom.map(GallerySceneMath::unitPoint)
            val mesh = GalleryExpandedMesh(figure, custom)
            custom.clear()
            repeat(32) { frame ->
                mesh.update(frame * mesh.periodSeconds / 32, 1f)
                anchors.forEach { anchor ->
                    for (dx in listOf(-.015f, .015f)) for (dy in listOf(-.015f, .015f)) {
                        assertTrue(displacement(mesh, anchor.x + dx, anchor.y + dy) < .00001f)
                    }
                }
            }
        }
    }

    @Test fun boundaryIsStillAndAllExcursionsStayWithinTheImageAndSubtleAmplitude() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            repeat(96) { frame ->
                mesh.update(frame * mesh.periodSeconds / 96, 1f)
                for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
                    val index = row * (mesh.columns + 1) + column
                    val dx = mesh.vertices[index * 2] - column.toFloat() / mesh.columns
                    val dy = mesh.vertices[index * 2 + 1] - row.toFloat() / mesh.rows
                    assertTrue("$figure displacement at $row,$column", hypot(dx, dy) <= .032f)
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

    @Test fun noMeshTriangleFoldsDuringAnyFigureCycle() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            repeat(192) { frame ->
                mesh.update(frame * mesh.periodSeconds / 192, 1f)
                for (row in 0 until mesh.rows) for (column in 0 until mesh.columns) {
                    val a = row * (mesh.columns + 1) + column
                    val b = a + 1
                    val c = a + mesh.columns + 1
                    val d = c + 1
                    assertTrue("$figure upper triangle at $frame,$row,$column", cross(mesh, a, b, c) > 0f)
                    assertTrue("$figure lower triangle at $frame,$row,$column", cross(mesh, b, d, c) > 0f)
                }
            }
        }
    }

    @Test fun eachPeriodJoinsWithoutPositionOrAlphaSeams() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            for (time in listOf(0.0, .7, 1.8, 4.9, -1.7)) {
                mesh.update(time, 300f)
                val vertices = mesh.vertices.copyOf()
                val colors = mesh.colors.copyOf()
                mesh.update(time + mesh.periodSeconds, 300f)
                vertices.indices.forEach { assertEquals(vertices[it], mesh.vertices[it], .00005f) }
                colors.indices.forEach { assertEquals(colors[it], mesh.colors[it]) }
            }
            mesh.update(-.0001, 1f)
            val before = mesh.vertices.copyOf()
            mesh.update(.0001, 1f)
            before.indices.forEach { assertTrue(abs(before[it] - mesh.vertices[it]) < .00001f) }
        }
    }

    @Test fun illuminationNeverTintsTheBitmapOrProducesAFlash() {
        figures.forEach { figure ->
            val mesh = mesh(figure)
            var leastAlpha = 255
            repeat(80) { frame ->
                mesh.update(frame * mesh.periodSeconds / 80, 1f)
                mesh.colors.forEach { color ->
                    assertTrue(color ushr 24 in 212..255)
                    assertEquals(0x00ffffff, color and 0x00ffffff)
                    leastAlpha = minOf(leastAlpha, color ushr 24)
                }
            }
            assertTrue("$figure local light must actually change", leastAlpha < 245)
        }
    }

    @Test fun eachRealIllustrationHasVisibleLocalMotionWithoutMovingItsAnatomy() {
        val movingSamples = mapOf(
            GalleryFigure.Cassiopeia to listOf(.20f to .82f, .86f to .84f),
            GalleryFigure.Leo to listOf(.45f to .12f, .19f to .49f, .87f to .26f),
            GalleryFigure.Scorpius to listOf(.915f to .605f),
            GalleryFigure.Pegasus to listOf(.84f to .25f, .535f to .16f, .90f to .72f),
            GalleryFigure.Delphinus to listOf(.13f to .725f, .33f to .86f),
        )
        val rigidSamples = mapOf(
            GalleryFigure.Cassiopeia to listOf(.51f to .17f, .50f to .38f, .28f to .43f,
                .77f to .46f, .29f to .66f, .75f to .63f, .60f to .55f, .52f to .82f),
            GalleryFigure.Leo to listOf(.18f to .30f, .26f to .27f, .34f to .21f,
                .46f to .57f, .70f to .56f, .25f to .85f, .37f to .84f,
                .62f to .86f, .87f to .85f, .92f to .47f),
            GalleryFigure.Scorpius to listOf(.28f to .17f, .17f to .45f, .43f to .44f,
                .57f to .58f, .63f to .69f, .68f to .77f, .32f to .62f,
                .44f to .73f, .53f to .35f, .69f to .40f),
            GalleryFigure.Pegasus to listOf(.14f to .35f, .24f to .40f, .36f to .54f,
                .42f to .47f, .52f to .58f, .22f to .64f, .15f to .77f,
                .64f to .78f, .75f to .85f, .71f to .64f),
            GalleryFigure.Delphinus to listOf(.87f to .17f, .78f to .23f, .57f to .39f,
                .47f to .23f, .71f to .45f, .81f to .45f, .41f to .59f, .35f to .72f),
        )
        figures.forEach { figure ->
            val mesh = mesh(figure)
            val maxima = FloatArray(movingSamples.getValue(figure).size)
            repeat(96) { frame ->
                mesh.update(frame * mesh.periodSeconds / 96, 1f)
                movingSamples.getValue(figure).forEachIndexed { index, (x, y) ->
                    maxima[index] = maxOf(maxima[index], displacement(mesh, x, y))
                }
                rigidSamples.getValue(figure).forEach { (x, y) ->
                    assertTrue("$figure anatomy at $x,$y frame $frame", displacement(mesh, x, y) < .00001f)
                }
            }
            maxima.forEachIndexed { index, maximum ->
                assertTrue("$figure moving part $index must move ($maximum)", maximum > .0026f)
                assertTrue("$figure moving part $index must stay subtle", maximum <= .032f)
            }
        }
    }

    @Test fun figuresHaveDistinctLocalMotionAndDistinctLoopDurations() {
        val meshes = figures.map(::mesh)
        assertEquals(figures.size, meshes.map { it.periodSeconds }.toSet().size)
        meshes.forEach { it.update(1.7, 1f) }
        for (first in meshes.indices) for (second in 0 until first) {
            assertTrue(!meshes[first].vertices.contentEquals(meshes[second].vertices))
            assertTrue(!meshes[first].colors.contentEquals(meshes[second].colors))
        }
    }

    @Test fun localGesturesCompleteSlightlySoonerWithoutBecomingRapidPulses() {
        val periods = mapOf(
            GalleryFigure.Cassiopeia to 8.6,
            GalleryFigure.Leo to 7.8,
            GalleryFigure.Scorpius to 9.2,
            GalleryFigure.Pegasus to 8.0,
            GalleryFigure.Delphinus to 6.8,
        )
        periods.forEach { (figure, period) -> assertEquals(period, mesh(figure).periodSeconds, 0.0) }
    }

    @Test fun strongerLocalGesturesCannotRegressToThePreviousFaintAmplitude() {
        // Each floor sits between the measured V20 and V21 peaks, with floating-point margin.
        // Check displacement from the undeformed mesh, not just a shorter loop duration.
        val minimumPeaks = mapOf(
            GalleryFigure.Cassiopeia to .0202f,
            GalleryFigure.Leo to .0189f,
            GalleryFigure.Scorpius to .0109f,
            GalleryFigure.Pegasus to .0198f,
            GalleryFigure.Delphinus to .0204f,
        )
        minimumPeaks.forEach { (figure, minimum) ->
            val mesh = mesh(figure)
            var peak = 0f
            repeat(192) { frame ->
                mesh.update(frame * mesh.periodSeconds / 192, 1f)
                for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
                    val index = row * (mesh.columns + 1) + column
                    peak = maxOf(peak, hypot(
                        mesh.vertices[index * 2] - column.toFloat() / mesh.columns,
                        mesh.vertices[index * 2 + 1] - row.toFloat() / mesh.rows,
                    ))
                }
            }
            assertTrue("$figure should retain its stronger local motion: $peak", peak >= minimum)
            assertTrue("$figure must remain restrained: $peak", peak <= .032f)
        }
    }

    @Test fun oldThreeFiguresDoNotAccidentallyAcquireAnotherFiguresMotion() {
        listOf(GalleryFigure.Cygnus, GalleryFigure.Orion, GalleryFigure.Lyra).forEach { figure ->
            val mesh = GalleryExpandedMesh(figure, emptyList())
            repeat(16) {
                mesh.update(it / 2.0, 411f)
                assertStill(mesh, 411f)
            }
        }
    }

    private fun points(figure: GalleryFigure): List<GalleryPoint> =
        GallerySamples.items.first { it.figure == figure }.points

    private fun mesh(figure: GalleryFigure) = GalleryExpandedMesh(figure, points(figure))

    private fun assertStill(mesh: GalleryExpandedMesh, side: Float) {
        for (row in 0..mesh.rows) for (column in 0..mesh.columns) {
            val index = row * (mesh.columns + 1) + column
            assertEquals(column.toFloat() / mesh.columns * side, mesh.vertices[index * 2], 0f)
            assertEquals(row.toFloat() / mesh.rows * side, mesh.vertices[index * 2 + 1], 0f)
            assertEquals(-1, mesh.colors[index])
        }
    }

    private fun displacement(mesh: GalleryExpandedMesh, x: Float, y: Float): Float {
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
        return hypot(component(0) - x, component(1) - y)
    }

    private fun cross(mesh: GalleryExpandedMesh, a: Int, b: Int, c: Int): Float {
        val ax = mesh.vertices[a * 2]
        val ay = mesh.vertices[a * 2 + 1]
        return (mesh.vertices[b * 2] - ax) * (mesh.vertices[c * 2 + 1] - ay) -
            (mesh.vertices[b * 2 + 1] - ay) * (mesh.vertices[c * 2] - ax)
    }
}
