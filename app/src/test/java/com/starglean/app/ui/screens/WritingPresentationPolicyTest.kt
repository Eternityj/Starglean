package com.starglean.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class WritingPresentationPolicyTest {
    @Test fun normalSavedDraftHasNoFooterMessage() {
        assertNull(writingDraftStatusMessage(false, true, WritingDraftUiState(loaded = true)))
    }

    @Test fun loadingAndPendingSaveAreStillVisible() {
        assertEquals("Loading your draft", writingDraftStatusMessage(false, false, WritingDraftUiState()))
        assertEquals("Saving your draft…", writingDraftStatusMessage(false, true,
            WritingDraftUiState(loaded = true, saving = true)))
    }

    @Test fun failureIsNeverHiddenByTheQuieterPresentation() {
        assertEquals("Draft not saved · Retry before closing", writingDraftStatusMessage(false, true,
            WritingDraftUiState(loaded = true, error = "Disk full", saving = true)))
        assertEquals("Demo content is not saved to your drafts", writingDraftStatusMessage(true, true,
            WritingDraftUiState(loaded = true)))
    }
}
