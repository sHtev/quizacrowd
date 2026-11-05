package dev.shtev.quizacrowd.quizapi

import dev.shtev.quizacrowd.common.QuestionStarted
import dev.shtev.quizacrowd.common.QuizEvent
import dev.shtev.quizacrowd.common.Standing
import dev.shtev.quizacrowd.common.ValkeyKeys
import dev.shtev.quizacrowd.common.quizJson
import io.lettuce.core.RedisClient
import io.lettuce.core.ZRange
import kotlinx.coroutines.future.await

/** quiz-api's use of Valkey: the open question, player names, standings and events. */
class Valkey(url: String) : AutoCloseable {
    private val client = RedisClient.create(url)
    private val connection = client.connect()
    // Lettuce's async commands return futures; await() suspends instead of blocking a thread.
    private val commands = connection.async()

    suspend fun publish(quizId: String, event: QuizEvent) {
        commands.publish(ValkeyKeys.events(quizId), quizJson.encodeToString<QuizEvent>(event)).await()
    }

    /** Everything ingest needs to check an answer and the gateway needs to resend the question. */
    suspend fun setCurrentQuestion(quizId: String, question: QuestionStarted) {
        val fields = mapOf(
            "questionId" to question.questionId,
            "index" to question.index.toString(),
            "total" to question.total.toString(),
            "startedAt" to question.startedAtEpochMs.toString(),
            "deadline" to question.deadlineEpochMs.toString(),
            "optionCount" to question.options.size.toString(),
            "text" to question.text,
            "options" to quizJson.encodeToString(question.options),
        )
        commands.hset(ValkeyKeys.currentQuestion(quizId), fields).await()
    }

    suspend fun clearCurrentQuestion(quizId: String) {
        commands.del(ValkeyKeys.currentQuestion(quizId)).await()
    }

    suspend fun recordName(quizId: String, playerId: String, name: String) {
        commands.hset(ValkeyKeys.names(quizId), playerId, name).await()
    }

    /** Highest scores first, with display names. */
    suspend fun topStandings(quizId: String, count: Int = 10): List<Standing> {
        val scores = commands.zrangeWithScores(ValkeyKeys.leaderboard(quizId), ZRange.byIndex(0, count - 1L).rev()).await()
        if (scores.isEmpty()) return emptyList()
        val names = commands.hmget(ValkeyKeys.names(quizId), *scores.map { it.value }.toTypedArray()).await()
            .associate { it.key to it.getValueOrElse("?") }
        return scores.map { Standing(names.getValue(it.value), it.score.toLong()) }
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}
