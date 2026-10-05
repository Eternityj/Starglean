package com.starglean.app.ui.space

import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryFigure
import com.starglean.app.ui.components.GalleryStarDust
import java.util.Collections
import kotlin.math.PI

/** A detached, read-only message from Compose to the GL thread. No diary text or location. */
internal data class HomeConstellation(
    val instanceId: String,
    val name: String,
    val geometry: CelestialGeometry.Constellation,
    val isCygnus: Boolean,
    val stars: List<ConstellationStyle.Star>,
    val filaments: List<ConstellationStyle.Mote>,
    val flecks: List<ConstellationStyle.Mote>,
    val diaryIds: List<String?>,
) {
    override fun toString(): String = "HomeConstellation(redacted)"
}

internal class HomeSkySnapshot private constructor(val figures: List<HomeConstellation>) {
    fun indexOf(instanceId: String?): Int? = instanceId?.let { id ->
        figures.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 }
    }

    fun diaryAt(instanceId: String, starIndex: Int): String? =
        figures.firstOrNull { it.instanceId == instanceId }?.diaryIds?.getOrNull(starIndex)

    override fun toString(): String = "HomeSkySnapshot(count=${figures.size})"

    companion object {
        val Empty = HomeSkySnapshot(emptyList())

        /** Only the explicit non-local DEBUG preview keeps the historical five figures. */
        val Demo: HomeSkySnapshot by lazy {
            HomeSkySnapshot(frozen(CelestialGeometry.constellations.mapIndexed { index, original ->
                HomeConstellation(
                    instanceId = "demo-home-$index", name = original.name,
                    geometry = original.copy(points = frozen(original.points), links = frozen(original.links)),
                    isCygnus = index == 0,
                    stars = frozen(original.points.indices.map { ConstellationFilaments.star(index, it) }),
                    filaments = frozen(ConstellationFilaments.motes(index)),
                    flecks = frozen(ConstellationFilaments.flecks(index)),
                    diaryIds = frozen(original.points.map { null }),
                )
            }))
        }

        /**
         * The domain supplies actual gallery instances and star→entry assignments.
         * Incomplete, hidden, duplicate-ID or unassigned instances never become a
         * pretend completed home constellation. Repeated template types are valid.
         */
        fun fromGallery(
            items: List<GalleryConstellation>,
            diaryAt: (String, Int) -> String?,
        ): HomeSkySnapshot {
            val eligible = items.asSequence().filter { it.shownOnHome && it.completed }
                .distinctBy { it.id }
                .map { item -> item to item.points.indices.map { diaryAt(item.id, it) } }
                .filter { (_, diaries) -> diaries.all { !it.isNullOrBlank() } }
                .take(8).toList()
            if (eligible.isEmpty()) return Empty
            return HomeSkySnapshot(frozen(eligible.mapIndexed { slot, (item, diaries) ->
                // Reuse approved dust only when its anchors really match this template.
                val geometryIndex = item.geometryIndex?.takeIf { index ->
                    val old = CelestialGeometry.constellations.getOrNull(index)
                    old != null && old.links == item.links && old.points.size == item.points.size &&
                        old.points.indices.all { old.points[it].x == item.points[it].x && old.points[it].y == item.points[it].y }
                }
                val copied = item.copy(points = frozen(item.points), links = frozen(item.links),
                    litStarIndices = Collections.unmodifiableSet(HashSet(item.litStarIndices)), geometryIndex = geometryIndex)
                HomeConstellation(
                    instanceId = item.id, name = "${item.name} · ${item.ordinal}",
                    geometry = CelestialGeometry.Constellation(
                        name = item.name,
                        points = frozen(item.points.map { CelestialGeometry.Vec3(it.x, it.y, 0f) }),
                        links = frozen(item.links), inclination = inclinations[slot],
                        phaseOffset = .25f + slot * (2f * PI.toFloat() / eligible.size),
                    ),
                    isCygnus = item.figure == GalleryFigure.Cygnus && geometryIndex == 0,
                    stars = frozen(item.points.indices.map { starIndex ->
                        geometryIndex?.let { ConstellationFilaments.star(it, starIndex) }
                            ?: ConstellationStyle.Star(
                                intensity = if (starIndex == 0) 1.35f else 1.12f,
                                scale = if (starIndex == 0) 1.40f else 1.14f,
                            )
                    }),
                    filaments = frozen(GalleryStarDust.motes(copied)),
                    flecks = frozen(GalleryStarDust.flecks(copied)),
                    diaryIds = frozen(diaries),
                )
            }))
        }

        private val inclinations = listOf(.64f, -.80f, .98f, -.40f, .22f, .74f, -.63f, .45f)
    }
}

internal fun homeConstellationPoints(figure: CelestialGeometry.Constellation, orbitPhase: Float): List<CelestialGeometry.Vec3> =
    figure.points.map { point ->
        CelestialGeometry.rotateX(CelestialGeometry.rotateY(
            CelestialGeometry.Vec3(point.x, point.y, CelestialGeometry.ORBIT_RADIUS + point.z),
            orbitPhase + figure.phaseOffset,
        ), figure.inclination)
    }

internal fun homeConstellationCenter(figure: CelestialGeometry.Constellation, orbitPhase: Float): CelestialGeometry.Vec3 =
    CelestialGeometry.rotateX(CelestialGeometry.rotateY(
        CelestialGeometry.Vec3(0f, 0f, CelestialGeometry.ORBIT_RADIUS), orbitPhase + figure.phaseOffset,
    ), figure.inclination)

private fun <T> frozen(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

/** Screen-space hit identity stays separate from decorative dust and diary contents. */
internal data class HomeStarHit(val index: Int, val starIndex: Int, val x: Float, val y: Float,
    val z: Float, val occluded: Boolean)

internal fun nearestHomeStar(hits: List<HomeStarHit>, x: Float, y: Float, selectedIndex: Int?, radius: Float): HomeStarHit? {
    if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0f) return null
    fun distanceSquared(hit: HomeStarHit): Float = (hit.x - x) * (hit.x - x) + (hit.y - y) * (hit.y - y)
    return hits.asSequence().filter {
        !it.occluded && it.x.isFinite() && it.y.isFinite() && it.z in -1f..1f &&
            (selectedIndex == null || it.index == selectedIndex)
    }.minByOrNull(::distanceSquared)?.takeIf { distanceSquared(it) < radius * radius }
}
