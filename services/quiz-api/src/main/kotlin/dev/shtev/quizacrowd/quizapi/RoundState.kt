package dev.shtev.quizacrowd.quizapi

/** Matches the check constraint on quizzes.state. */
enum class QuizState { LOBBY, QUESTION_OPEN, QUESTION_CLOSED, ENDED }

/** What the host can do: POST /api/quizzes/{id}/next, /close or /end. */
enum class HostAction { NEXT, CLOSE, END }

/**
 * The state [action] moves a quiz to from [from], or null if the host can't do that now.
 * [hasNextQuestion] says whether any question is still unasked.
 */
fun transition(from: QuizState, action: HostAction, hasNextQuestion: Boolean): QuizState? {
    if (from == QuizState.ENDED) return null
    return when (action) {
        HostAction.END -> QuizState.ENDED
        HostAction.CLOSE if from == QuizState.QUESTION_OPEN -> QuizState.QUESTION_CLOSED
        HostAction.NEXT if from != QuizState.QUESTION_OPEN && hasNextQuestion -> QuizState.QUESTION_OPEN
        else -> null
    }
}
