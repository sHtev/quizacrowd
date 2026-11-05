package dev.shtev.quizacrowd.quizapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamesTest {
    @Test
    fun surroundingSpaceTrimmed() {
        assertEquals("Ada", cleanDisplayName("  Ada  "))
    }

    @Test
    fun emptyOrBlankRejected() {
        assertNull(cleanDisplayName(""))
        assertNull(cleanDisplayName("   "))
    }

    @Test
    fun upTo24CharactersAllowed() {
        assertEquals("a".repeat(24), cleanDisplayName("a".repeat(24)))
        assertNull(cleanDisplayName("a".repeat(25)))
    }

    @Test
    fun controlCharactersRejected() {
        assertNull(cleanDisplayName("Ada\nLovelace"))
        assertNull(cleanDisplayName("Ada\u0000"))
    }

    @Test
    fun emojiCountAsOneCharacterEach() {
        // Each of these emoji is two UTF-16 chars on the JVM; a player sees one character.
        val twentyFourThumbs = "👍".repeat(24)
        assertEquals(twentyFourThumbs, cleanDisplayName(twentyFourThumbs))
    }
}
