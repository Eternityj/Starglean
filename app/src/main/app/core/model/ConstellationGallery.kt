package com.starglean.app.core.model

import java.util.Locale

/** Local tangent-plane coordinates, centred on the constellation, with +Y pointing up. */
data class GalleryPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "A constellation point must be finite" }
    }
}

/** Illustration identity is independent of the homepage renderer's geometry catalog. */
enum class GalleryFigure { Cygnus, Orion, Lyra, Cassiopeia, Leo, Scorpius, Pegasus, Delphinus }

/**
 * A gallery instance, not a constellation type. Only these explicit points can contain diaries;
 * the renderer's decorative dust and illustration must never create additional diary slots.
 */
data class GalleryConstellation(
    val id: String,
    val name: String,
    val latinName: String,
    val ordinal: Int,
    val geometryIndex: Int?,
    val points: List<GalleryPoint>,
    val links: List<Pair<Int, Int>>,
    val litStarIndices: Set<Int>,
    val shownOnHome: Boolean,
    val figure: GalleryFigure? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && latinName.isNotBlank())
        require(ordinal > 0)
        require(geometryIndex == null || geometryIndex >= 0)
        require(points.isNotEmpty())
        require(litStarIndices.all { it in points.indices })
        require(links.all { (from, to) -> from in points.indices && to in points.indices && from != to })
        require(!shownOnHome || litStarIndices.size == points.size) {
            "Only completed instances can be shown on the home screen"
        }
    }

    val totalStars: Int get() = points.size
    val litStars: Int get() = litStarIndices.size
    val completed: Boolean get() = litStars == totalStars
    val hasIllustration: Boolean get() = figure != null

    /** Stable within this local prototype; browsing, sorting and changing target do not renumber it. */
    fun starId(index: Int): String {
        require(index in points.indices)
        return "demo:$id:star:${index + 1}"
    }

    /** Explicit local fixture identity, never presented as a fetched or persisted diary. */
    fun diaryIdForStar(index: Int): String? {
        require(index in points.indices)
        return if (index in litStarIndices) "demo:$id:diary:${index + 1}" else null
    }
}

/**
 * Separate, explicit gallery fixtures: the old DemoContent counts are not real diary records.
 * Coordinates/links with a geometryIndex mirror ui/space/CelestialGeometry.kt, keeping the
 * model independent of the renderer. ConstellationGalleryTest checks this mirror for drift.
 * The other four figures use artistic, simplified arrangements inspired by familiar shapes,
 * not precise celestial coordinates or a complete astronomical catalog. Image anchors map to
 * the unit canvas as u = .50 + .68 * x, v = .52 - .68 * y.
 * Ursa Minor is deliberately not invented here: its old fixture has no matching home geometry.
 */
object GallerySamples {
    const val initialFocusedId = "cygnus-1"
    const val initialTargetId = "lyra-1"

    val items: List<GalleryConstellation> = listOf(
        GalleryConstellation(
            id = "cygnus-1", name = "Cygnus", latinName = "Cygnus", ordinal = 1,
            geometryIndex = 0,
            points = listOf(
                GalleryPoint(.06f, .51f), GalleryPoint(0f, .10f),
                GalleryPoint(-.38f, .02f), GalleryPoint(.38f, .20f),
                GalleryPoint(-.08f, -.23f), GalleryPoint(-.18f, -.51f),
            ),
            links = listOf(0 to 1, 2 to 1, 1 to 3, 1 to 4, 4 to 5),
            litStarIndices = (0..5).toSet(), shownOnHome = true, figure = GalleryFigure.Cygnus,
        ),
        GalleryConstellation(
            id = "orion-1", name = "Orion", latinName = "Orion", ordinal = 1,
            geometryIndex = 1,
            points = listOf(
                GalleryPoint(-.31f, .34f), GalleryPoint(.28f, .42f),
                GalleryPoint(-.02f, .58f), GalleryPoint(-.13f, .03f),
                GalleryPoint(0f, 0f), GalleryPoint(.13f, -.03f),
                GalleryPoint(-.26f, -.46f), GalleryPoint(.32f, -.40f),
                GalleryPoint(.015f, -.22f),
            ),
            links = listOf(0 to 2, 2 to 1, 0 to 3, 1 to 5, 3 to 4, 4 to 5, 3 to 6, 5 to 7, 4 to 8),
            litStarIndices = (0..8).toSet(), shownOnHome = true, figure = GalleryFigure.Orion,
        ),
        GalleryConstellation(
            id = "lyra-1", name = "Lyra", latinName = "Lyra", ordinal = 1,
            geometryIndex = 2,
            points = listOf(
                GalleryPoint(-.23f, .39f), GalleryPoint(-.08f, .16f),
                GalleryPoint(.29f, .09f), GalleryPoint(.18f, -.30f),
                GalleryPoint(-.19f, -.22f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 1),
            litStarIndices = (0..3).toSet(), shownOnHome = false, figure = GalleryFigure.Lyra,
        ),
        GalleryConstellation(
            id = "cassiopeia-1", name = "Cassiopeia", latinName = "Cassiopeia", ordinal = 1,
            geometryIndex = 3,
            points = listOf(
                GalleryPoint(-.47f, .25f), GalleryPoint(-.23f, -.21f),
                GalleryPoint(0f, .10f), GalleryPoint(.24f, -.17f),
                GalleryPoint(.47f, .31f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
            litStarIndices = (0..4).toSet(), shownOnHome = false, figure = GalleryFigure.Cassiopeia,
        ),
        GalleryConstellation(
            id = "leo-1", name = "Leo", latinName = "Leo", ordinal = 1,
            geometryIndex = null,
            // A left-facing sickle above the foreleg, joined to the right-hand body triangle.
            points = listOf(
                GalleryPoint(-.30f, -.10f), GalleryPoint(-.26f, .18f),
                GalleryPoint(-.38f, .35f), GalleryPoint(-.27f, .47f),
                GalleryPoint(-.08f, .42f), GalleryPoint(-.03f, .24f),
                GalleryPoint(.48f, -.04f), GalleryPoint(.25f, .14f),
                GalleryPoint(.17f, -.20f),
            ),
            links = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 1,
                1 to 7, 7 to 6, 6 to 8, 8 to 0,
            ),
            litStarIndices = (0..5).toSet(), shownOnHome = false, figure = GalleryFigure.Leo,
        ),
        GalleryConstellation(
            id = "scorpius-1", name = "Scorpius", latinName = "Scorpius", ordinal = 1,
            geometryIndex = null,
            // Upper-left claws lead down the body into the distinct, upward-hooked tail.
            points = listOf(
                GalleryPoint(-.38f, .42f), GalleryPoint(-.13f, .33f),
                GalleryPoint(-.34f, .16f), GalleryPoint(-.17f, .10f),
                GalleryPoint(-.03f, -.05f), GalleryPoint(.07f, -.25f),
                GalleryPoint(.24f, -.40f), GalleryPoint(.42f, -.31f),
                GalleryPoint(.45f, -.09f), GalleryPoint(.32f, 0f),
            ),
            links = listOf(
                0 to 1, 2 to 1, 1 to 3, 3 to 4, 4 to 5,
                5 to 6, 6 to 7, 7 to 8, 8 to 9,
            ),
            litStarIndices = (0..9).toSet(), shownOnHome = false, figure = GalleryFigure.Scorpius,
        ),
        GalleryConstellation(
            id = "pegasus-1", name = "Pegasus", latinName = "Pegasus", ordinal = 1,
            geometryIndex = null,
            // The central Great Square anchors the body; neck and foreleg extend to the left.
            points = listOf(
                GalleryPoint(-.23f, .23f), GalleryPoint(.30f, .30f),
                GalleryPoint(.33f, -.20f), GalleryPoint(-.22f, -.27f),
                GalleryPoint(-.40f, .38f), GalleryPoint(-.50f, .20f),
                GalleryPoint(-.41f, -.06f), GalleryPoint(-.51f, -.33f),
            ),
            links = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 0,
                0 to 4, 4 to 5, 3 to 6, 6 to 7,
            ),
            litStarIndices = (0..3).toSet(), shownOnHome = false, figure = GalleryFigure.Pegasus,
        ),
        GalleryConstellation(
            id = "delphinus-1", name = "Delphinus", latinName = "Delphinus", ordinal = 1,
            geometryIndex = null,
            // A small diamond at the leaping dolphin's chest with a lower-left tail anchor.
            points = listOf(
                GalleryPoint(.04f, .32f), GalleryPoint(.25f, .42f),
                GalleryPoint(.39f, .21f), GalleryPoint(.19f, .10f),
                GalleryPoint(-.34f, -.34f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 3 to 4),
            litStarIndices = (0..4).toSet(), shownOnHome = false, figure = GalleryFigure.Delphinus,
        ),
    )

    val initialHomeIds: Set<String> = items.filter { it.shownOnHome }.map { it.id }.toSet()
}

object GallerySelectionPolicy {
    const val MAX_HOME_INSTANCES = 8
    const val MAX_INSTANCES_PER_TYPE = 5

    /** A completed instance cannot receive a new diary. Merely browsing never calls this. */
    fun selectTarget(currentId: String?, candidate: GalleryConstellation): String? =
        if (candidate.completed) currentId else candidate.id

    /** Editing only a draft; removal is always allowed, including clearing every selection. */
    fun toggleHomeDraft(draftIds: Set<String>, item: GalleryConstellation): Set<String> = when {
        item.id in draftIds -> draftIds - item.id
        !item.completed || draftIds.size >= MAX_HOME_INSTANCES -> draftIds
        else -> draftIds + item.id
    }

    /** Revalidate at confirmation, including stale IDs and newly incomplete catalog entries. */
    fun confirmHomeDraft(draftIds: Set<String>, items: List<GalleryConstellation>): Set<String>? {
        if (draftIds.size > MAX_HOME_INSTANCES) return null
        if (items.map { it.id }.toSet().size != items.size) return null
        val completedIds = items.filter { it.completed }.map { it.id }.toSet()
        return draftIds.takeIf { completedIds.containsAll(it) }?.toSet()
    }

    /** Both completed and unfinished instances count towards a type's five-instance limit. */
    fun canAddInstance(items: List<GalleryConstellation>, latinName: String): Boolean {
        val type = latinName.trim().lowercase(Locale.ROOT)
        if (type.isEmpty()) return false
        return items.count { it.latinName.trim().lowercase(Locale.ROOT) == type } < MAX_INSTANCES_PER_TYPE
    }
}

/** Draft and committed sets are intentionally distinct: dismissing the editor never commits. */
data class GalleryHomeSelection(
    val committedIds: Set<String>,
    val draftIds: Set<String> = committedIds,
) {
    fun begin(): GalleryHomeSelection = copy(draftIds = committedIds.toSet())

    fun toggle(item: GalleryConstellation): GalleryHomeSelection =
        copy(draftIds = GallerySelectionPolicy.toggleHomeDraft(draftIds, item))

    fun confirm(items: List<GalleryConstellation>): GalleryHomeSelection {
        val valid = GallerySelectionPolicy.confirmHomeDraft(draftIds, items) ?: return this
        return GalleryHomeSelection(committedIds = valid, draftIds = valid)
    }

    fun cancel(): GalleryHomeSelection = copy(draftIds = committedIds.toSet())
}

/** Focusing a collection page does not silently change the destination of the next diary. */
data class GalleryBrowsingState(val focusedId: String, val targetId: String?) {
    fun browse(item: GalleryConstellation): GalleryBrowsingState = copy(focusedId = item.id)

    fun setTarget(item: GalleryConstellation): GalleryBrowsingState =
        copy(targetId = GallerySelectionPolicy.selectTarget(targetId, item))
}
