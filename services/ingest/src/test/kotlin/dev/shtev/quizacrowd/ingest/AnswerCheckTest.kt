package dev.shtev.quizacrowd.ingest

import dev.shtev.quizacrowd.ingest.Verdict.ACCEPTED
import dev.shtev.quizacrowd.ingest.Verdict.CLOSED
import dev.shtev.quizacrowd.ingest.Verdict.INVALID_CHOICE
import kotlin.test.Test
import kotlin.test.assertEquals

class AnswerCheckTest {
    private val open = OpenQuestion(questionId = "q1", deadlineEpochMs = 20_000, optionCount = 4)

    @Test
    fun acceptedWhileOpen() {
        assertEquals(ACCEPTED, checkAnswer(open, "q1", choice = 0, nowEpochMs = 5_000))
        assertEquals(ACCEPTED, checkAnswer(open, "q1", choice = 3, nowEpochMs = 5_000))
    }

    @Test
    fun acceptedAtTheDeadline() {
        assertEquals(ACCEPTED, checkAnswer(open, "q1", choice = 1, nowEpochMs = 20_000))
    }

    @Test
    fun closedWhenNoQuestionIsOpen() {
        assertEquals(CLOSED, checkAnswer(null, "q1", choice = 1, nowEpochMs = 5_000))
    }

    @Test
    fun closedForAnyOtherQuestion() {
        assertEquals(CLOSED, checkAnswer(open, "q0", choice = 1, nowEpochMs = 5_000))
    }

    @Test
    fun closedAfterTheDeadline() {
        assertEquals(CLOSED, checkAnswer(open, "q1", choice = 1, nowEpochMs = 20_001))
    }

    @Test
    fun choiceMustBeOneOfTheOptions() {
        assertEquals(INVALID_CHOICE, checkAnswer(open, "q1", choice = -1, nowEpochMs = 5_000))
        assertEquals(INVALID_CHOICE, checkAnswer(open, "q1", choice = 4, nowEpochMs = 5_000))
    }

    @Test
    fun closedTakesPrecedenceOverABadChoice() {
        // Once the question has gone, the phone just needs telling it's too late.
        assertEquals(CLOSED, checkAnswer(null, "q1", choice = 99, nowEpochMs = 5_000))
        assertEquals(CLOSED, checkAnswer(open, "q1", choice = 99, nowEpochMs = 20_001))
    }
}
