package com.starglean.app.core.online

import com.starglean.app.core.account.AccountIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationCloudControllerPolicyTest {
    @Test fun `applying the confirmed cloud snapshot does not schedule a redundant write`() {
        val confirmed = CloudConstellationState(emptyList(), null, emptyList(), emptyList())

        assertFalse(needsConstellationPush(confirmed.copy(), confirmed))
        assertTrue(needsConstellationPush(confirmed, null))
        assertTrue(needsConstellationPush(confirmed.copy(targetId = "changed"), confirmed))
    }

    @Test fun `local changes may push only against the last confirmed remote version`() {
        assertTrue(mayPushUnchangedRemote(7, 7))
        assertFalse(mayPushUnchangedRemote(7, 8))
        assertFalse(mayPushUnchangedRemote(-1, -1))
    }
    private val account = AccountIdentity("76f91c57-41c5-48d0-9ed8-e9588cda1f3c", "reader@example.test", false)

    @Test fun `local revision pushes only from an enabled settled conflict-free account`() {
        val ready = ConstellationCloudUiState(account = account, enabled = true, version = 4)

        assertTrue(shouldPushConstellationRevision(ready))
        assertFalse(shouldPushConstellationRevision(ready.copy(account = null)))
        assertFalse(shouldPushConstellationRevision(ready.copy(enabled = false)))
        assertFalse(shouldPushConstellationRevision(ready.copy(busy = true)))
        assertFalse(shouldPushConstellationRevision(ready.copy(conflict = ConstellationSyncConflict.VERSION_CHANGED)))
        assertFalse(shouldPushConstellationRevision(ready.copy(conflict = ConstellationSyncConflict.CLOUD_HAS_STATE)))
        assertFalse(shouldPushConstellationRevision(ready.copy(conflict = ConstellationSyncConflict.CLOUD_ENTRIES_NOT_ON_DEVICE)))
        assertFalse(shouldPushConstellationRevision(ready.copy(conflict = ConstellationSyncConflict.LOCAL_ENTRIES_NOT_IN_CLOUD)))
    }
}
