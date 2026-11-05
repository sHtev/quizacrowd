package dev.shtev.quizacrowd.common

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {
    private fun typeOf(event: QuizEvent): String =
        Regex(""""type":"([^"]+)"""").find(quizJson.encodeToString<QuizEvent>(event))!!.groupValues[1]

    @Test
    fun questionStartedRoundTrips() {
        val event: QuizEvent = QuestionStarted("q1", 0, 10, "2+2?", listOf("3", "4", "5", "22"), 1000, 21000)
        val json = quizJson.encodeToString(event)
        assertTrue(""""type":"question"""" in json, json)
        assertEquals(event, quizJson.decodeFromString<QuizEvent>(json))
    }

    @Test
    fun eachEventHasItsTypeName() {
        val top = listOf(Standing("Ada", 900))
        assertEquals("closed", typeOf(QuestionClosed("q1", 1)))
        assertEquals("leaderboard", typeOf(LeaderboardUpdated(top, answered = 3)))
        assertEquals("ended", typeOf(QuizEnded(top)))
        assertEquals("you", typeOf(YourStanding(rank = 1, score = 900)))
    }
}
