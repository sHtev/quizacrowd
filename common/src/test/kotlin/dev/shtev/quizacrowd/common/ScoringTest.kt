package dev.shtev.quizacrowd.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    // A 20-second question: opens at 1000 ms, closes at 21000 ms.
    private val started = 1_000L
    private val deadline = 21_000L

    @Test
    fun wrongAnswerScoresZero() {
        assertEquals(0, points(correct = false, receivedAtMs = started, startedAtMs = started, deadlineMs = deadline))
    }

    @Test
    fun instantCorrectAnswerScores1000() {
        assertEquals(1000, points(correct = true, receivedAtMs = started, startedAtMs = started, deadlineMs = deadline))
    }

    @Test
    fun lastMomentCorrectScores500() {
        assertEquals(500, points(correct = true, receivedAtMs = deadline, startedAtMs = started, deadlineMs = deadline))
    }

    @Test
    fun halfwayScores750() {
        assertEquals(750, points(correct = true, receivedAtMs = 11_000L, startedAtMs = started, deadlineMs = deadline))
    }

    @Test
    fun afterDeadlineScoresZero() {
        assertEquals(0, points(correct = true, receivedAtMs = deadline + 1, startedAtMs = started, deadlineMs = deadline))
    }
}
