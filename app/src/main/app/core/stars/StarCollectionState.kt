package com.starglean.app.core.stars

import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GallerySamples
import com.starglean.app.core.model.GallerySelectionPolicy

/** Reuses approved artistic geometry, never the gallery's example diary progress. */
object StarConstellationTemplates {
    val items: List<GalleryConstellation> = GallerySamples.items.map {
        it.copy(litStarIndices = emptySet(), shownOnHome = false)
    }

    fun find(templateId: String): GalleryConstellation? = items.firstOrNull { it.id == templateId }
}

data class StarConstellationInstance(val id: String, val templateId: String, val ordinal: Int)

/** An index into the template's explicit points; decorative stars are not diary slots. */
data class DiaryStarPlacement(val diaryId: String, val instanceId: String, val starIndex: Int)

data class StarCollectionState(
    val instances: List<StarConstellationInstance>,
    val targetId: String?,
    val homeIds: Set<String>,
    val placements: List<DiaryStarPlacement>,
) {
    init { StarCollectionRules.validate(this) }

    /** Stable creation order, including multiple instances of the same template. */
    fun galleryItems(): List<GalleryConstellation> = instances.map { instance ->
        val template = requireNotNull(StarConstellationTemplates.find(instance.templateId))
        template.copy(
            id = instance.id,
            ordinal = instance.ordinal,
            litStarIndices = placements.filter { it.instanceId == instance.id }.map { it.starIndex }.toSet(),
            shownOnHome = instance.id in homeIds,
        )
    }

    fun placementForDiary(id: String): DiaryStarPlacement? = placements.firstOrNull { it.diaryId == id }

    /** The real saved diary ID, not GalleryConstellation.diaryIdForStar's demo identity. */
    fun diaryAt(instanceId: String, index: Int): String? =
        placements.firstOrNull { it.instanceId == instanceId && it.starIndex == index }?.diaryId

    companion object {
        fun initial(): StarCollectionState = StarCollectionState(
            instances = StarConstellationTemplates.items.map { StarConstellationInstance(it.id, it.id, 1) },
            targetId = null,
            homeIds = emptySet(),
            placements = emptyList(),
        )
    }
}

data class StarPlacementResult(
    val state: StarCollectionState,
    val placement: DiaryStarPlacement?,
    val newlyPlaced: Boolean,
    /** Current completion state. Play a completion animation only when newlyPlaced is also true. */
    val completed: Boolean,
)

data class StarRemovalResult(
    val state: StarCollectionState,
    val placement: DiaryStarPlacement?,
    val removedFromHome: Boolean,
)

class StarCollectionDataException(message: String) : IllegalArgumentException(message)

/** Pure, bounded rules. The store supplies verified IDs from OwnDiaryStore, not caller assertions. */
object StarCollectionRules {
    const val MAX_INSTANCES_PER_TYPE = GallerySelectionPolicy.MAX_INSTANCES_PER_TYPE
    const val MAX_HOME_INSTANCES = GallerySelectionPolicy.MAX_HOME_INSTANCES
    val maxInstances: Int get() = StarConstellationTemplates.items.size * MAX_INSTANCES_PER_TYPE
    val maxPlacements: Int get() = StarConstellationTemplates.items.sumOf { it.totalStars } * MAX_INSTANCES_PER_TYPE
    private val ownId = Regex("own-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validate(state: StarCollectionState) {
        if (state.instances.size !in StarConstellationTemplates.items.size..maxInstances ||
            state.homeIds.size > MAX_HOME_INSTANCES || state.placements.size > maxPlacements) invalid()
        if (state.instances.map { it.id }.toSet().size != state.instances.size) invalid()
        val byId = state.instances.associateBy { it.id }
        state.instances.forEach { instance ->
            val template = StarConstellationTemplates.find(instance.templateId) ?: invalid()
            if (instance.ordinal !in 1..MAX_INSTANCES_PER_TYPE ||
                instance.id != instanceId(template.id, instance.ordinal)) invalid()
        }
        // No deletion in this format: every type retains its first instance and contiguous ordinals.
        StarConstellationTemplates.items.forEach { template ->
            val ordinals = state.instances.filter { it.templateId == template.id }.map { it.ordinal }.sorted()
            if (ordinals.isEmpty() || ordinals != (1..ordinals.size).toList()) invalid()
        }
        if (state.targetId != null && state.targetId !in byId) invalid()
        if (state.placements.map { it.diaryId }.toSet().size != state.placements.size ||
            state.placements.map { it.instanceId to it.starIndex }.toSet().size != state.placements.size) invalid()
        state.placements.forEach { placement ->
            if (!ownId.matches(placement.diaryId)) invalid()
            val instance = byId[placement.instanceId] ?: invalid()
            val template = StarConstellationTemplates.find(instance.templateId) ?: invalid()
            if (placement.starIndex !in template.points.indices) invalid()
        }
        if (state.homeIds.any { it !in byId || !isComplete(state, it) }) invalid()
    }

    fun validateSavedReferences(state: StarCollectionState, savedDiaryIds: Set<String>) {
        validate(state)
        if (state.placements.any { it.diaryId !in savedDiaryIds }) {
            throw StarCollectionDataException("A constellation references an entry that could not be found. Existing data is preserved; no changes were made.")
        }
    }

    fun setTarget(state: StarCollectionState, instanceId: String?): StarCollectionState {
        validate(state)
        if (instanceId != null && state.instances.none { it.id == instanceId }) {
            throw StarCollectionDataException("This constellation could not be found. Reload your collection and try again.")
        }
        if (instanceId != null && isComplete(state, instanceId)) {
            throw StarCollectionDataException("This constellation is complete. Choose one with an empty star.")
        }
        return if (state.targetId == instanceId) state else state.copy(targetId = instanceId)
    }

    fun createInstance(state: StarCollectionState, templateId: String): StarCollectionState {
        validate(state)
        val template = StarConstellationTemplates.find(templateId)
            ?: throw StarCollectionDataException("Choose a constellation from the available collection.")
        val count = state.instances.count { it.templateId == template.id }
        if (count >= MAX_INSTANCES_PER_TYPE) {
            throw StarCollectionDataException("You can keep up to five instances of each constellation. Your existing entries are safe.")
        }
        val ordinal = count + 1
        return state.copy(instances = state.instances + StarConstellationInstance(instanceId(template.id, ordinal), template.id, ordinal))
    }

    fun placeDiary(
        state: StarCollectionState,
        diaryId: String,
        savedDiaryIds: Set<String>,
        instanceId: String? = null,
    ): StarPlacementResult {
        validateSavedReferences(state, savedDiaryIds)
        if (!ownId.matches(diaryId) || diaryId !in savedDiaryIds) {
            throw StarCollectionDataException("Only a saved entry from My entries can be placed in a constellation.")
        }
        // A retry never moves an entry, even if the target changed in the meantime.
        state.placementForDiary(diaryId)?.let {
            return StarPlacementResult(state, it, newlyPlaced = false, completed = isComplete(state, it.instanceId))
        }
        val target = instanceId ?: state.targetId
            ?: return StarPlacementResult(state, null, newlyPlaced = false, completed = false)
        val instance = state.instances.firstOrNull { it.id == target }
            ?: throw StarCollectionDataException("This constellation could not be found. Your entry is still saved in My entries.")
        val template = requireNotNull(StarConstellationTemplates.find(instance.templateId))
        val occupied = state.placements.filter { it.instanceId == target }.map { it.starIndex }.toSet()
        val index = template.points.indices.firstOrNull { it !in occupied }
            ?: return StarPlacementResult(state, null, newlyPlaced = false, completed = false)
        val placement = DiaryStarPlacement(diaryId, target, index)
        val next = state.copy(placements = state.placements + placement)
        return StarPlacementResult(next, placement, newlyPlaced = true, completed = isComplete(next, target))
    }

    /** Removes only this diary's stable slot; instances and every other slot keep their identity. */
    fun removeDiary(state: StarCollectionState, diaryId: String): StarRemovalResult {
        validate(state)
        if (!ownId.matches(diaryId)) {
            throw StarCollectionDataException("This entry could not be identified safely. Reload the collection and try again.")
        }
        val placement = state.placementForDiary(diaryId)
            ?: return StarRemovalResult(state, null, removedFromHome = false)
        val remaining = state.placements.filterNot { it.diaryId == diaryId }
        val wasHome = placement.instanceId in state.homeIds
        val nextHome = if (wasHome && !isComplete(state.copy(placements = remaining, homeIds = emptySet()), placement.instanceId)) {
            state.homeIds - placement.instanceId
        } else state.homeIds
        val next = state.copy(placements = remaining, homeIds = nextHome)
        return StarRemovalResult(next, placement, wasHome && placement.instanceId !in nextHome)
    }

    fun setHome(state: StarCollectionState, instanceIds: Set<String>): StarCollectionState {
        validate(state)
        if (instanceIds.size > MAX_HOME_INSTANCES) {
            throw StarCollectionDataException("Choose up to eight completed constellations for the planet.")
        }
        if (instanceIds.any { id -> state.instances.none { it.id == id } || !isComplete(state, id) }) {
            throw StarCollectionDataException("Only completed constellations can be shown on the planet. Reload your collection and try again.")
        }
        return if (state.homeIds == instanceIds) state else state.copy(homeIds = instanceIds.toSet())
    }

    fun isComplete(state: StarCollectionState, instanceId: String): Boolean {
        val instance = state.instances.firstOrNull { it.id == instanceId } ?: return false
        val template = StarConstellationTemplates.find(instance.templateId) ?: return false
        return state.placements.count { it.instanceId == instanceId } == template.totalStars
    }

    private fun instanceId(templateId: String, ordinal: Int): String = "${templateId.removeSuffix("-1")}-$ordinal"

    private fun invalid(): Nothing = throw StarCollectionDataException(
        "The local constellation collection could not be read safely. The original is preserved. Do not clear app data.",
    )
}
