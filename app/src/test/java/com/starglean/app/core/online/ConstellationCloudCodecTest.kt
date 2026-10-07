package com.starglean.app.core.online

import com.starglean.app.core.stars.DiaryStarPlacement
import com.starglean.app.core.stars.StarCollectionRules
import com.starglean.app.core.stars.StarCollectionState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationCloudCodecTest {
    @Test fun `round trip preserves instance and placement ordering`() {
        var local = StarCollectionState.initial()
        local = StarCollectionRules.createInstance(local, local.instances.first().templateId)
        val diaryA = "own-${UUID.randomUUID()}"
        val diaryB = "own-${UUID.randomUUID()}"
        local = local.copy(placements = listOf(
            DiaryStarPlacement(diaryA, local.instances.first().id, 0),
            DiaryStarPlacement(diaryB, local.instances.last().id, 0),
        ), targetId = local.instances.last().id)
        val links = listOf(
            CloudEntryLink(UUID.randomUUID().toString(), diaryA, UUID.randomUUID()),
            CloudEntryLink(UUID.randomUUID().toString(), diaryB, UUID.randomUUID()),
        )

        val cloud = ConstellationCloudCodec.fromLocal(local, links)
        val decoded = ConstellationCloudCodec.fromWire(ConstellationCloudCodec.toWire(cloud))

        assertEquals(cloud, decoded)
        assertEquals(local, ConstellationCloudCodec.toLocal(decoded, links))
    }

    @Test fun `unmapped local diary is never silently omitted`() {
        val initial = StarCollectionState.initial()
        val local = initial.copy(placements = listOf(DiaryStarPlacement("own-${UUID.randomUUID()}", initial.instances.first().id, 0)))

        val result = runCatching { ConstellationCloudCodec.fromLocal(local, emptyList()) }

        assertTrue(result.exceptionOrNull() is ConstellationSyncException)
    }

    @Test fun `cloud state cannot cross account mappings`() {
        val local = StarCollectionState.initial()
        val entry = UUID.randomUUID()
        val cloud = CloudConstellationState(local.instances, null, emptyList(),
            listOf(CloudStarPlacement(entry, local.instances.first().id, 0)))

        val result = runCatching { ConstellationCloudCodec.toLocal(cloud, emptyList()) }

        assertTrue(result.exceptionOrNull() is ConstellationSyncException)
    }
}
