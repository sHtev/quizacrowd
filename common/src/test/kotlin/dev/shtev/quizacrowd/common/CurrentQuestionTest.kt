package dev.shtev.quizacrowd.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentQuestionTest {
    @Test
    fun hashRoundTrips() {
        val question = QuestionStarted(
            questionId = "q1",
            index = 2,
            total = 10,
            text = "Which of these is not a \"car\" maker?",
            options = listOf("Nissan", "Ducati", "GMC, Inc.", "Fiat"),
            startedAtEpochMs = 1_000,
            deadlineEpochMs = 21_000,
        )
        val hash = question.toCurrentQuestionHash()
        assertEquals("4", hash["optionCount"])
        assertEquals(question, questionFromHash(hash))
    }

    @Test
    fun emptyHashIsNoQuestion() = assertNull(questionFromHash(emptyMap()))
}
