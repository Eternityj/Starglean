package com.starglean.app.ui.screens

import com.starglean.app.core.model.GalleryFigure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationEnglishCopyTest {
    @Test fun everyFigureExplainsItsIllustrationWithoutRepeatingTheConstellationName() {
        val subtitles = listOf("The Swan", "The Hunter", "The Lyre", "The Queen", "The Lion", "The Scorpion", "The Winged Horse", "The Dolphin")
        assertEquals(subtitles, GalleryFigure.entries.map(::galleryFigureSubtitle))
        assertEquals("Among the Stars", galleryFigureSubtitle(null))
    }

    @Test fun reflectionsRemainDistinctConciseAndMatchTheEditionLanguage() {
        val reflections = GalleryFigure.entries.map(::galleryFigureReflection)
        assertEquals(8, reflections.toSet().size)
        assertEquals("Every small light is worth keeping.", galleryFigureReflection(null))
        (reflections + galleryFigureReflection(null)).forEach { text ->
            assertTrue(text.length in 15..55)
        }
    }
}
