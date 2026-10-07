package com.starglean.app.core.online

import com.starglean.app.core.stars.DiaryStarPlacement
import com.starglean.app.core.stars.StarCollectionRules
import com.starglean.app.core.stars.StarCollectionState
import com.starglean.app.core.stars.StarConstellationInstance
import java.util.UUID

data class CloudStarPlacement(val entryId: UUID, val instanceId: String, val starIndex: Int)
data class CloudConstellationState(
    val instances: List<StarConstellationInstance>,
    val targetId: String?,
    val homeIds: List<String>,
    val placements: List<CloudStarPlacement>,
)

object ConstellationCloudCodec {
    fun fromLocal(local: StarCollectionState, links: List<CloudEntryLink>): CloudConstellationState {
        StarCollectionRules.validate(local)
        val byLocal = links.associateBy { it.localDiaryId }
        if (local.placements.any { it.diaryId !in byLocal }) {
            throw ConstellationSyncException("Some stars belong only to local entries. Restore or publish explicit cloud counterparts before enabling sync; no diary content was uploaded.")
        }
        return CloudConstellationState(
            instances = local.instances,
            targetId = local.targetId,
            homeIds = local.instances.filter { it.id in local.homeIds }.map { it.id },
            placements = local.placements.map { placement ->
                CloudStarPlacement(requireNotNull(byLocal[placement.diaryId]).cloudEntryId, placement.instanceId, placement.starIndex)
            },
        ).also(::validate)
    }

    fun toLocal(cloud: CloudConstellationState, links: List<CloudEntryLink>): StarCollectionState {
        validate(cloud)
        val byCloud = links.associateBy { it.cloudEntryId }
        if (cloud.placements.any { it.entryId !in byCloud }) {
            throw ConstellationSyncException("Restore every referenced cloud entry to this device before restoring its constellation.")
        }
        return StarCollectionState(
            cloud.instances,
            cloud.targetId,
            cloud.homeIds.toSet(),
            cloud.placements.map { DiaryStarPlacement(requireNotNull(byCloud[it.entryId]).localDiaryId, it.instanceId, it.starIndex) },
        ).also(StarCollectionRules::validate)
    }

    fun toWire(value: CloudConstellationState): Map<String, Any?> {
        validate(value)
        return linkedMapOf(
            "instances" to value.instances.map { linkedMapOf("id" to it.id, "template_id" to it.templateId, "ordinal" to it.ordinal) },
            "target_id" to value.targetId,
            "home_ids" to value.homeIds,
            "placements" to value.placements.map { linkedMapOf("entry_id" to it.entryId.toString(), "instance_id" to it.instanceId, "star_index" to it.starIndex) },
        )
    }

    fun fromWire(value: Map<String, Any?>): CloudConstellationState {
        if (value.keys != setOf("instances", "target_id", "home_ids", "placements")) invalid()
        val instances = value.objects("instances").map { StarConstellationInstance(it.string("id"), it.string("template_id"), it.int("ordinal")) }
        val target = value["target_id"]?.let { it as? String ?: invalid() }
        val home = value.strings("home_ids")
        val placements = value.objects("placements").map {
            CloudStarPlacement(runCatching { UUID.fromString(it.string("entry_id")) }.getOrElse { invalid() }, it.string("instance_id"), it.int("star_index"))
        }
        return CloudConstellationState(instances, target, home, placements).also(::validate)
    }

    fun isEmpty(value: CloudConstellationState): Boolean = value.placements.isEmpty() && value.targetId == null && value.homeIds.isEmpty() &&
        value.instances == StarCollectionState.initial().instances

    private fun validate(value: CloudConstellationState) {
        if (value.homeIds.toSet().size != value.homeIds.size || value.placements.map { it.entryId }.toSet().size != value.placements.size) invalid()
        // Substitute syntactically valid local IDs to reuse the complete topology invariant without exposing local identities.
        val projected = StarCollectionState(value.instances, value.targetId, value.homeIds.toSet(), value.placements.mapIndexed { index, placement ->
            val suffix = index.toString(16).padStart(12, '0')
            DiaryStarPlacement("own-00000000-0000-0000-0000-$suffix", placement.instanceId, placement.starIndex)
        })
        StarCollectionRules.validate(projected)
    }

    @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.objects(key: String) =
        (this[key] as? List<*>)?.map { it as? Map<String, Any?> ?: invalid() } ?: invalid()
    private fun Map<String, Any?>.strings(key: String) = (this[key] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: invalid()
    private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: invalid()
    private fun invalid(): Nothing = throw ConstellationSyncException("The cloud constellation state is invalid. Local stars were preserved.")
}

class ConstellationSyncException(message: String) : Exception(message)
