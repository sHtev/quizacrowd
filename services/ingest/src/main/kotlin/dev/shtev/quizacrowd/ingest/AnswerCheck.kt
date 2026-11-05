package dev.shtev.quizacrowd.ingest

import io.ktor.http.content.VersionListProperty

/** The open question as ingest needs it, read from the quiz:{id}:current hash. */
data class OpenQuestion(val questionId: String, val deadlineEpochMs: Long, val optionCount: Int)

enum class Verdict { ACCEPTED, CLOSED, INVALID_CHOICE }

/**
 * Whether an answer to [questionId] can be queued. [open] is null when no question is open.
 * An answer exactly at the deadline still counts.
 */
fun checkAnswer(open: OpenQuestion?, questionId: String, choice: Int, nowEpochMs: Long): Verdict {
    if (open == null || questionId != open.questionId || nowEpochMs > open.deadlineEpochMs) return Verdict.CLOSED
    if (choice in 0 until open.optionCount) return Verdict.ACCEPTED
    return Verdict.INVALID_CHOICE
}
