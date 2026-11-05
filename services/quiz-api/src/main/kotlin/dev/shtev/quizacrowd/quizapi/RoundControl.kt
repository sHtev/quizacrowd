package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.QuestionClosed
import dev.shtev.quizacrowd.common.QuestionStarted
import dev.shtev.quizacrowd.common.QuizEnded
import dev.shtev.quizacrowd.quizapi.db.Questions
import dev.shtev.quizacrowd.quizapi.db.Quizzes
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Runs the host's next/close/end: checks [transition], records the change in Postgres,
 * then updates Valkey and tells every connected phone and screen.
 */
class RoundControl(
    private val db: Database,
    private val valkey: Valkey,
    private val questionSeconds: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** False if the action is out of order; nothing changes and the caller answers 409. */
    suspend fun apply(quizId: UUID, action: HostAction): Boolean {
        val (from, currentIndex, total) = db.query {
            val quiz = Quizzes.selectAll().where { Quizzes.id eq quizId }.single()
            val total = Questions.selectAll().where { Questions.quizId eq quizId }.count()
            Triple(QuizState.valueOf(quiz[Quizzes.state]), quiz[Quizzes.currentIndex], total)
        }
        val nextIndex = (currentIndex ?: -1) + 1
        val to = transition(from, action, hasNextQuestion = nextIndex < total) ?: return false

        return when (action) {
            HostAction.NEXT -> openQuestion(quizId, from, to, nextIndex, total.toInt())
            HostAction.CLOSE -> closeQuestion(quizId, from, to, currentIndex!!)
            HostAction.END -> endQuiz(quizId, from, to)
        }
    }

    private suspend fun openQuestion(quizId: UUID, from: QuizState, to: QuizState, index: Int, total: Int): Boolean {
        val startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val deadline = startedAt.plusSeconds(questionSeconds.toLong())
        val question = db.query {
            if (!moveState(quizId, from, to, index)) return@query null
            val where = (Questions.quizId eq quizId) and (Questions.idx eq index)
            Questions.update({ where }) {
                it[Questions.startedAt] = startedAt.atOffset(ZoneOffset.UTC)
                it[Questions.deadline] = deadline.atOffset(ZoneOffset.UTC)
            }
            val row = Questions.selectAll().where { where }.single()
            QuestionStarted(
                questionId = row[Questions.id].toString(),
                index = index,
                total = total,
                text = row[Questions.text],
                options = row[Questions.answerOptions],
                startedAtEpochMs = startedAt.toEpochMilli(),
                deadlineEpochMs = deadline.toEpochMilli(),
            )
        } ?: return false

        // Hash first, so a phone that reconnects as the event goes out still finds the question.
        valkey.setCurrentQuestion(quizId.toString(), question)
        valkey.publish(quizId.toString(), question)
        return true
    }

    private suspend fun closeQuestion(quizId: UUID, from: QuizState, to: QuizState, index: Int): Boolean {
        val closed = db.query {
            if (!moveState(quizId, from, to, index)) return@query null
            val row = Questions.selectAll().where { (Questions.quizId eq quizId) and (Questions.idx eq index) }.single()
            QuestionClosed(row[Questions.id].toString(), row[Questions.correctIndex])
        } ?: return false

        valkey.clearCurrentQuestion(quizId.toString())
        valkey.publish(quizId.toString(), closed)
        return true
    }

    private suspend fun endQuiz(quizId: UUID, from: QuizState, to: QuizState): Boolean {
        val moved = db.query { moveState(quizId, from, to, index = null) }
        if (!moved) return false

        valkey.clearCurrentQuestion(quizId.toString())
        valkey.publish(quizId.toString(), QuizEnded(valkey.topStandings(quizId.toString())))
        return true
    }

    /**
     * Moves the quiz only if it's still in [from]. If two clicks race, Postgres lets one
     * update win; the other matches no row and gets false. A null [index] leaves current_index alone.
     */
    private fun moveState(quizId: UUID, from: QuizState, to: QuizState, index: Int?): Boolean =
        Quizzes.update({ (Quizzes.id eq quizId) and (Quizzes.state eq from.name) }) {
            it[state] = to.name
            if (index != null) it[currentIndex] = index
        } == 1
}
