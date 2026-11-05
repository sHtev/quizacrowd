package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.quizapi.HostAction.CLOSE
import dev.shtev.quizacrowd.quizapi.HostAction.END
import dev.shtev.quizacrowd.quizapi.HostAction.NEXT
import dev.shtev.quizacrowd.quizapi.QuizState.ENDED
import dev.shtev.quizacrowd.quizapi.QuizState.LOBBY
import dev.shtev.quizacrowd.quizapi.QuizState.QUESTION_CLOSED
import dev.shtev.quizacrowd.quizapi.QuizState.QUESTION_OPEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoundStateTest {
    @Test
    fun nextOpensAQuestionFromLobbyOrAfterClose() {
        assertEquals(QUESTION_OPEN, transition(LOBBY, NEXT, hasNextQuestion = true))
        assertEquals(QUESTION_OPEN, transition(QUESTION_CLOSED, NEXT, hasNextQuestion = true))
    }

    @Test
    fun nextNeedsAQuestionLeft() {
        assertNull(transition(LOBBY, NEXT, hasNextQuestion = false))
        assertNull(transition(QUESTION_CLOSED, NEXT, hasNextQuestion = false))
    }

    @Test
    fun nextRejectedWhileAQuestionIsOpen() {
        assertNull(transition(QUESTION_OPEN, NEXT, hasNextQuestion = true))
    }

    @Test
    fun closeOnlyWhileOpen() {
        assertEquals(QUESTION_CLOSED, transition(QUESTION_OPEN, CLOSE, hasNextQuestion = true))
        assertNull(transition(LOBBY, CLOSE, hasNextQuestion = true))
        assertNull(transition(QUESTION_CLOSED, CLOSE, hasNextQuestion = true))
    }

    @Test
    fun lastQuestionCanBeClosed() {
        assertEquals(QUESTION_CLOSED, transition(QUESTION_OPEN, CLOSE, hasNextQuestion = false))
    }

    @Test
    fun endFromAnyStateExceptEnded() {
        for (state in listOf(LOBBY, QUESTION_OPEN, QUESTION_CLOSED)) {
            assertEquals(ENDED, transition(state, END, hasNextQuestion = true), "end from $state")
            assertEquals(ENDED, transition(state, END, hasNextQuestion = false), "end from $state")
        }
    }

    @Test
    fun nothingAfterEnd() {
        for (action in HostAction.entries) {
            assertNull(transition(ENDED, action, hasNextQuestion = true), "$action after end")
        }
    }
}
