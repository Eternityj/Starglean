package com.starglean.app.core.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide lock for files participating in local diary, draft, star and photo cleanup. */
object LibraryStorageCoordinator {
    val mutex = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revisionFlow: StateFlow<Long> = mutableRevision.asStateFlow()

    internal fun publishChangeLocked() {
        mutableRevision.value = mutableRevision.value + 1L
    }
}
