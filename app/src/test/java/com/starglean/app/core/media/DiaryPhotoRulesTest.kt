package com.starglean.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryPhotoRulesTest {
    @Test fun acceptsTwoDifferentNormalizedPhotos() {
        val photos = listOf(photo("1"), photo("2"))
        DiaryPhotoRules.validateAttachments(photos)
        assertEquals(2, photos.size)
    }

    @Test(expected = PhotoStoreException::class)
    fun rejectsMoreThanTwoPhotos() {
        DiaryPhotoRules.validateAttachments(listOf(photo("1"), photo("2"), photo("3")))
    }

    @Test(expected = PhotoStoreException::class)
    fun rejectsDuplicateReferences() {
        DiaryPhotoRules.validateAttachments(listOf(photo("1"), photo("1")))
    }

    @Test fun opaqueReferenceGrammarDoesNotAcceptPaths() {
        assertTrue(DiaryPhotoRules.isValidRef("photo-00000000-0000-0000-0000-000000000001"))
        assertFalse(DiaryPhotoRules.isValidRef("../../photo-00000000-0000-0000-0000-000000000001"))
        assertFalse(DiaryPhotoRules.isValidRef("file:///private/photo.jpg"))
    }

    @Test fun debugStringRedactsReference() {
        val photo = photo("1")
        assertFalse(photo.toString().contains(photo.ref))
        assertTrue(photo.toString().contains("ref=<private>"))
    }

    private fun photo(suffix: String) = DiaryPhoto(
        ref = "photo-00000000-0000-0000-0000-${suffix.padStart(12, '0')}",
        width = 1200,
        height = 800,
        sizeBytes = 1024,
    )
}
